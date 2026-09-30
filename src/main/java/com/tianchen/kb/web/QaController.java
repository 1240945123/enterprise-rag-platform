package com.tianchen.kb.web;

import com.tianchen.kb.qa.QaService;
import com.tianchen.kb.web.dto.AskRequest;
import com.tianchen.kb.web.dto.AskResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 问答接口：检索 → 引用核对 → 生成，返回带引用清单的答案。 */
@RestController
@RequestMapping(path = "/api/v1/qa", produces = MediaType.APPLICATION_JSON_VALUE)
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        var answer = qaService.answer(
                request.knowledgeBaseId(), request.question(), request.topK());
        return ResponseEntity.ok(
                AskResponse.from(answer, request.knowledgeBaseId(), request.question()));
    }
}
