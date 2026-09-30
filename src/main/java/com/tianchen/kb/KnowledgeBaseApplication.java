package com.tianchen.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业级 AI 知识库与智能问答平台。
 *
 * <p>启动入口。本地运行前需先通过 Docker 启动 PGvector：
 * <pre>{@code docker compose up -d pgvector}</pre>
 */
@SpringBootApplication
public class KnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseApplication.class, args);
    }
}
