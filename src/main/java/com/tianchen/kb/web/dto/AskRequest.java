package com.tianchen.kb.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 问答请求。topK 为空时使用默认 Top-K。 */
public record AskRequest(
        @NotBlank String knowledgeBaseId,
        @NotBlank String question,
        Integer topK) {
}
