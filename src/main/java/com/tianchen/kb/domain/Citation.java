package com.tianchen.kb.domain;

/**
 * 引用溯源结果：回答中的每一句主张都应对应至少一个命中切片。
 */
public record Citation(
        String documentId,
        String title,
        int seq,
        String snippet,
        double score) {

    /** 引用是否足以支撑回答：切片必须有实际内容且相似度超过阈值。 */
    public boolean acceptable(double minScore) {
        return score >= minScore && snippet != null && !snippet.isBlank();
    }
}
