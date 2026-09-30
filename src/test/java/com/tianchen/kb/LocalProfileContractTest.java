package com.tianchen.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.tianchen.kb.domain.Citation;
import com.tianchen.kb.ingest.IngestService;
import com.tianchen.kb.retrieval.RetrievalService;
import com.tianchen.kb.testsupport.DeterministicEmbeddingModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * 零基础设施集成测试：用 {@code local} profile 的内存向量库跑通摄取与检索，
 * 不依赖 Docker 与 PostgreSQL，也不调用大模型。
 *
 * <p>向量模型替换为 {@link DeterministicEmbeddingModel}，因此测试完全离线可跑：
 * 不需要下载 ONNX 模型、不需要云端 API Key、不消耗额度。
 *
 * <p>重点锁住两个曾经出错的契约：
 * <ol>
 *   <li><b>重复摄取不产生重复向量</b>——同一文档摄取两次后，检索结果里不应出现重复切片；</li>
 *   <li><b>分数是「越大越相关」的相似度</b>——查询原句的分数必须严格高于无关句。</li>
 * </ol>
 * 这两条分别对应「幂等只做了一半」与「相似度被 1-x 倒转」两个真实缺陷。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 关掉 transformers 自动装配的 EmbeddingModel——否则它会尝试联网下载 ONNX 模型，
        // 测试便不再离线可跑。向量模型由下面的 StubEmbeddingConfiguration 提供。
        "spring.ai.model.embedding=none",
        // 阈值设为 0，让所有召回都返回，便于观察真实分数分布
        "kb.retrieval.min-score=0.0",
})
class LocalProfileContractTest {

    /** 用确定性桩模型覆盖自动装配的 EmbeddingModel，隔离网络与模型文件依赖。 */
    @TestConfiguration
    static class StubEmbeddingConfiguration {
        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new DeterministicEmbeddingModel();
        }
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private RetrievalService retrievalService;

    @Test
    @DisplayName("同一文档重复摄取不会留下重复切片")
    void reingestDoesNotDuplicateChunks() {
        String kb = "kb-dup-check";
        String content = "住宿费实行限额管理，一线城市每人每晚上限 600 元。"
                + "住宿发票抬头须与公司全称一致。";

        ingestService.ingest(kb, "差旅费管理办法", content);
        ingestService.ingest(kb, "差旅费管理办法", content);
        ingestService.ingest(kb, "差旅费管理办法", content);

        List<Citation> hits = retrievalService.search(kb, "一线城市住宿费上限", 10);
        assertThat(hits).isNotEmpty();

        long distinctKeys = hits.stream()
                .map(c -> c.documentId() + "#" + c.seq())
                .distinct()
                .count();
        assertThat(distinctKeys)
                .as("同一 (documentId, seq) 不应出现多次，否则说明重复摄取产生了重复向量")
                .isEqualTo(hits.size());
    }

    @Test
    @DisplayName("分数是相似度：查询原句的分数量严格高于无关句")
    void exactSentenceScoresHigherThanUnrelatedOne() {
        String kb = "kb-score-check";
        String sentence = "住宿费实行限额管理，一线城市每人每晚上限 600 元，二线城市上限 450 元。";
        ingestService.ingest(kb, "差旅费报销管理办法", sentence);

        double exact = topScore(kb, sentence);
        double unrelated = topScore(kb, "量子纠缠的退相干时间如何测量");

        assertThat(exact)
                .as("检索分数必须是相似度（越大越相关）。若此处失败，通常是误对 Spring AI 的 "
                        + "Document#getScore() 又做了一次 1-x 换算，把方向倒转了")
                .isGreaterThan(unrelated);
    }

    @Test
    @DisplayName("分数落在 [0,1] 区间内，便于业务解释")
    void scoresAreNormalized() {
        String kb = "kb-range-check";
        ingestService.ingest(kb, "入职指引", "入职首日上午九点到岗，地点为公司总部人力资源部。");

        List<Citation> hits = retrievalService.search(kb, "入职当天几点到岗", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(citation ->
                assertThat(citation.score()).isBetween(0.0d, 1.0d));
    }

    private double topScore(String kb, String question) {
        return retrievalService.search(kb, question, 10).stream()
                .mapToDouble(Citation::score)
                .max()
                .orElse(0.0d);
    }
}