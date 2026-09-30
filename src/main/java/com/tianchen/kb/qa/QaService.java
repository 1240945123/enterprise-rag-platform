package com.tianchen.kb.qa;

import com.tianchen.kb.config.KnowledgeBaseProperties;
import com.tianchen.kb.domain.Citation;
import com.tianchen.kb.domain.QaAnswer;
import com.tianchen.kb.observability.QaMetrics;
import com.tianchen.kb.retrieval.RetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 问答服务（RAG 主链路）。
 *
 * <p>链路：向量检索 → 引用核对 →（通过则）拼装带编号上下文 → 大模型生成 → 回传引用清单。
 * 未通过引用核对时直接拒答并说明原因，不调用大模型，避免无依据输出。
 *
 * <p>过程中把「落地」与「拒答」分别计入 {@link QaMetrics}，
 * 使拒答率可被 Prometheus 观测——这是判断知识库覆盖度与召回质量的核心业务指标。
 */
@Service
public class QaService {

    private static final String NO_GROUNDING_MESSAGE =
            "未在当前知识库中检索到足够支撑该问题的资料，无法给出有依据的回答。请补充相关文档后重试。";

    private final RetrievalService retrievalService;
    private final ChatClient chatClient;
    private final CitationValidator validator;
    private final QaMetrics metrics;
    private final Timer qaTimer;

    public QaService(
            RetrievalService retrievalService,
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseProperties properties,
            QaMetrics metrics,
            MeterRegistry meterRegistry) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClientBuilder.build();
        this.metrics = metrics;
        this.validator = new CitationValidator(
                properties.qa().minCitationScore(),
                properties.qa().minCitations());
        this.qaTimer = Timer.builder("kb.qa.latency")
                .description("端到端问答耗时")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
    }

    public QaAnswer answer(String knowledgeBaseId, String question, Integer topK) {
        return qaTimer.record(() -> doAnswer(knowledgeBaseId, question, topK));
    }

    private QaAnswer doAnswer(String knowledgeBaseId, String question, Integer topK) {
        long startedAt = System.currentTimeMillis();
        List<Citation> retrieved = retrievalService.search(knowledgeBaseId, question, topK);
        List<Citation> valid = validator.valid(retrieved);

        if (!validator.grounded(valid)) {
            metrics.recordRejected();
            return new QaAnswer(NO_GROUNDING_MESSAGE, List.of(), retrieved.size(), false, elapsed(startedAt));
        }

        String answer = chatClient.prompt()
                .system("你是企业知识库问答助手。只能依据给定的资料作答，不得臆测。"
                        + "回答中引用资料时请在句末标注来源编号，例如 [1]。"
                        + "若资料不足以回答，请明确说明资料不足。")
                .user(user -> user
                        .text("参考资料：\n{context}\n\n问题：{question}")
                        .param("context", validator.formatContext(valid))
                        .param("question", question))
                .call()
                .content();

        String safeAnswer = answer == null || answer.isBlank() ? "模型未返回有效内容。" : answer;
        metrics.recordGrounded();
        return new QaAnswer(safeAnswer, valid, retrieved.size(), true, elapsed(startedAt));
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}