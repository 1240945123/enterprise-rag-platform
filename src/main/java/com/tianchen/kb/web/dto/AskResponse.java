package com.tianchen.kb.web.dto;

import com.tianchen.kb.domain.Citation;
import com.tianchen.kb.domain.QaAnswer;
import java.util.List;

/** 问答响应：答案 + 引用清单 + 是否落地。 */
public record AskResponse(
        String answer,
        List<Citation> citations,
        int retrieved,
        boolean grounded,
        long elapsedMillis,
        String knowledgeBaseId,
        String question) {

    public static AskResponse from(QaAnswer answer, String knowledgeBaseId, String question) {
        return new AskResponse(
                answer.answer(),
                answer.citations(),
                answer.retrieved(),
                answer.grounded(),
                answer.elapsedMillis(),
                knowledgeBaseId,
                question);
    }
}
