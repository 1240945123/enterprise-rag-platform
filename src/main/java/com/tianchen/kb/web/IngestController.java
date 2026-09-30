package com.tianchen.kb.web;

import com.tianchen.kb.ingest.IngestResult;
import com.tianchen.kb.ingest.IngestService;
import com.tianchen.kb.web.dto.IngestRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文档摄取接口：把一篇文档切分并写入指定知识库。 */
@RestController
@RequestMapping(path = "/api/v1/ingest", produces = MediaType.APPLICATION_JSON_VALUE)
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequest request) {
        IngestResult result = ingestService.ingest(
                request.knowledgeBaseId(), request.title(), request.content());
        return ResponseEntity.ok(result.toSummary());
    }
}
