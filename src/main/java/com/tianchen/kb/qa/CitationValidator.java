package com.tianchen.kb.qa;

import com.tianchen.kb.domain.Citation;
import java.util.List;

/**
 * 引用核对器：剔除不足以支撑答案的引用。
 *
 * <p>这是本平台控制幻觉的核心环节——答案必须能被至少一个高置信切片支撑，
 * 否则整体判定为「无依据」，服务返回拒答说明而不是编造内容。
 */
public final class CitationValidator {

    private final double minScore;
    private final int minCitations;

    public CitationValidator(double minScore, int minCitations) {
        this.minScore = minScore;
        this.minCitations = minCitations;
    }

    /** 保留可接受的引用。 */
    public List<Citation> valid(List<Citation> citations) {
        if (citations == null) {
            return List.of();
        }
        return citations.stream().filter(citation -> citation.acceptable(minScore)).toList();
    }

    /** 是否足以支撑生成回答。null 视为没有依据。 */
    public boolean grounded(List<Citation> citations) {
        return citations != null && !citations.isEmpty() && citations.size() >= minCitations;
    }

    /** 把引用格式化为给大模型看的上下文块（带编号，便于模型在答案里标注来源）。 */
    public String formatContext(List<Citation> citations) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < citations.size(); index++) {
            Citation citation = citations.get(index);
            builder.append('[')
                    .append(index + 1)
                    .append("] 来源：")
                    .append(citation.title())
                    .append(" #")
                    .append(citation.seq())
                    .append("\n")
                    .append(citation.snippet())
                    .append("\n\n");
        }
        return builder.toString();
    }
}
