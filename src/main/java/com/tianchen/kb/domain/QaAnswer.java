package com.tianchen.kb.domain;

/**
 * 问答结果：答案 + 引用清单 + 检索统计。
 */
public record QaAnswer(
        String answer,
        java.util.List<Citation> citations,
        int retrieved,
        boolean grounded,
        long elapsedMillis) {
}
