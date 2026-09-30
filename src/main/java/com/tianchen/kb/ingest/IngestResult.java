package com.tianchen.kb.ingest;

import com.tianchen.kb.domain.DocumentChunk;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 文档摄取：读取文本、切分成重叠窗口、写入向量库。
 *
 * <p>切片元数据中统一携带 knowledge_base_id / document_id / title / seq，
 * 供检索阶段做多租户过滤与引用回溯。
 */
public final class IngestResult {

    private final String documentId;
    private final int chunkCount;
    private final List<DocumentChunk> chunks;

    public IngestResult(String documentId, int chunkCount, List<DocumentChunk> chunks) {
        this.documentId = documentId;
        this.chunkCount = chunkCount;
        this.chunks = chunks;
    }

    public String documentId() {
        return documentId;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public List<DocumentChunk> chunks() {
        return chunks;
    }

    public Map<String, Object> toSummary() {
        return Map.of(
                "documentId", documentId,
                "chunkCount", chunkCount,
                "createdAt", Instant.now().toString());
    }
}
