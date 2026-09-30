package com.tianchen.kb.domain;

import java.time.Instant;

/**
 * 文档切片。一个切片是检索的最小单元，也是引用溯源的最小单元。
 */
public record DocumentChunk(
        String chunkId,
        String knowledgeBaseId,
        String documentId,
        String title,
        int seq,
        String content,
        Instant createdAt) {

    public DocumentChunk {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("chunk content must not be blank");
        }
    }
}
