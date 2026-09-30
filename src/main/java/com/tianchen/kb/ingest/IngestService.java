package com.tianchen.kb.ingest;

import com.tianchen.kb.config.KnowledgeBaseProperties;
import com.tianchen.kb.domain.DocumentChunk;
import com.tianchen.kb.support.DigestUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.stereotype.Service;

/**
 * 文档摄取服务（ETL 层）。
 *
 * <p>流程：原文归一化 → 递归切分（含重叠窗口）→ 构造切片 → 写入向量库。
 *
 * <p><b>幂等性</b>：文档 ID 由「知识库 ID + 标题 + 正文」摘要生成，同一内容重复摄取得到同一 ID。
 * 更关键的是，每个切片的 {@link Document} 显式携带确定性 ID（{@code chunkId}），
 * 并在写入前按 {@code document_id} 清掉该文档的旧向量——这样重复摄取是 <em>覆盖</em> 而非
 * <em>追加</em>。否则向量库会堆积重复切片，挤占检索结果名额，并让引用清单出现同源重复项。
 */
@Service
public class IngestService {

    private final VectorStore vectorStore;
    private final TextSplitter splitter;

    public IngestService(VectorStore vectorStore, KnowledgeBaseProperties properties) {
        this.vectorStore = vectorStore;
        this.splitter = new TextSplitter(
                properties.ingest().chunkSize(),
                properties.ingest().overlap());
    }

    public IngestResult ingest(String knowledgeBaseId, String title, String content) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        String safeTitle = (title == null || title.isBlank()) ? "untitled" : title;
        String documentId = DigestUtils.shortId(knowledgeBaseId + "|" + safeTitle + "|" + content);

        List<String> chunks = splitter.split(content);
        List<Document> documents = new ArrayList<>(chunks.size());
        List<DocumentChunk> records = new ArrayList<>(chunks.size());
        Instant now = Instant.now();

        for (int index = 0; index < chunks.size(); index++) {
            String chunkId = DigestUtils.shortId(documentId + "|" + index);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("knowledge_base_id", knowledgeBaseId);
            metadata.put("document_id", documentId);
            metadata.put("title", safeTitle);
            metadata.put("seq", index);
            metadata.put("chunk_id", chunkId);
            metadata.put("created_at", now.toString());

            String text = chunks.get(index);
            // 显式传 id：不传会落到 Document(String, Map) 构造器，内部生成随机 UUID，
            // 导致同一逻辑切片在向量库里拥有不同 key，幂等随之失效。
            documents.add(new Document(chunkId, text, metadata));
            records.add(new DocumentChunk(
                    chunkId, knowledgeBaseId, documentId, safeTitle, index, text, now));
        }

        // 先清旧、再写新：保证同一文档重复摄取只保留一份向量。
        vectorStore.delete(filterByDocument(documentId));
        vectorStore.add(documents);
        return new IngestResult(documentId, chunks.size(), records);
    }

    public TextSplitter splitter() {
        return splitter;
    }

    /** 构造 {@code document_id == '…'} 过滤表达式，值做单引号转义。 */
    private static Filter.Expression filterByDocument(String documentId) {
        String escaped = documentId.replace("'", "''");
        return new FilterExpressionTextParser().parse("document_id == '" + escaped + "'");
    }

    /** 供测试断言使用：过滤表达式构造逻辑是否正确（含转义）。 */
    static String documentFilterText(String documentId) {
        return "document_id == '" + documentId.replace("'", "''") + "'";
    }
}