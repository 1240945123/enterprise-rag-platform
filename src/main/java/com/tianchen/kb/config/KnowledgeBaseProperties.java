package com.tianchen.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 知识库检索与问答的可调参数，全部可在 application.yml 或环境变量中覆盖。 */
@ConfigurationProperties(prefix = "kb")
public record KnowledgeBaseProperties(
        Ingest ingest,
        Retrieval retrieval,
        Qa qa) {

    public KnowledgeBaseProperties {
        ingest = ingest == null ? new Ingest(800, 120) : ingest;
        // 缺省阈值与 application.yml 保持一致（0.5，按 text-embedding-v3 标定）。
        // 两处不一致会让「配置缺失时的行为」变得难以预测。
        retrieval = retrieval == null ? new Retrieval(5, 0.5) : retrieval;
        qa = qa == null ? new Qa(0.5, 1) : qa;
    }

    /** 文档摄取参数。 */
    public record Ingest(int chunkSize, int overlap) {
    }

    /** 检索参数：Top-K 与相似度下限。 */
    public record Retrieval(int topK, double minScore) {
    }

    /** 生成参数：引用落地说阈值与最小支撑引用数。 */
    public record Qa(double minCitationScore, int minCitations) {
    }
}
