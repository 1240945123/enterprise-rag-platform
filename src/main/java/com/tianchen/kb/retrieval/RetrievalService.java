package com.tianchen.kb.retrieval;

import com.tianchen.kb.config.KnowledgeBaseProperties;
import com.tianchen.kb.domain.Citation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * 检索服务：按知识库隔离做向量召回，并把分数规范化为 0~1 的相似度。
 *
 * <p><b>分数口径（易错点）</b>：Spring AI 的 {@link Document#getScore()} 返回的是
 * <em>相似度</em>——越大越相关，且框架已跨向量库统一过口径
 * （{@code SimpleVectorStore} 直接给余弦相似度；PGvector 内部把距离换算成相似度）。
 * 因此这里 <b>不能</b>再套一层 {@code 1 - score}：那会把「越相关分数越低」倒转过来，
 * 既让阈值判断失效，也让排序与展示分数自相矛盾。
 *
 * <p>这里只做两件事：把分数裁剪到 [0, 1] 便于业务解释，以及按阈值兜底过滤。
 */
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final KnowledgeBaseProperties properties;

    public RetrievalService(VectorStore vectorStore, KnowledgeBaseProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<Citation> search(String knowledgeBaseId, String question, Integer topK) {
        int limit = topK == null || topK <= 0 ? properties.retrieval().topK() : topK;
        double minScore = properties.retrieval().minScore();

        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(limit)
                .similarityThreshold(minScore)
                .filterExpression("knowledge_base_id == '" + escape(knowledgeBaseId) + "'")
                .build();

        List<Citation> citations = new ArrayList<>();
        for (Document document : vectorStore.similaritySearch(request)) {
            Map<String, Object> metadata = document.getMetadata();
            double score = normalize(document.getScore());
            if (score < minScore) {
                continue;
            }
            citations.add(new Citation(
                    String.valueOf(metadata.getOrDefault("document_id", "")),
                    String.valueOf(metadata.getOrDefault("title", "")),
                    asInt(metadata.get("seq")),
                    document.getText(),
                    score));
        }
        // 兜底保持「越相关越靠前」，不依赖底层向量库的返回顺序。
        citations.sort((a, b) -> Double.compare(b.score(), a.score()));
        return List.copyOf(citations);
    }

    /** 相似度裁剪到 [0, 1]；缺失时按 0 处理（视为不可用）。 */
    private static double normalize(Double score) {
        if (score == null || score.isNaN()) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 过滤表达式里的值做最小转义，防止单引号把表达式截断。 */
    private static String escape(String value) {
        return value.replace("'", "''");
    }
}