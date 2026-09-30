package com.tianchen.kb.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 摄取请求。 */
public record IngestRequest(
        @NotBlank String knowledgeBaseId,
        String title,
        @NotBlank String content) {
}
