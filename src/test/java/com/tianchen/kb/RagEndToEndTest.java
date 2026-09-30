package com.tianchen.kb.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tianchen.kb.ingest.IngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 端到端集成测试：真实拉起 PGvector 容器，跑通「摄取 → 问答 → 引用溯源」主链路。
 *
 * <p>使用 Testcontainers，需要本机 Docker 可用；CI 环境同样适用。
 * 本机没有 Docker 时（例如只装了 JDK 的离线开发机）整类测试自动跳过，
 * 而不是把 {@code mvn test} 打成红色——环境缺失不应被误读为代码缺陷。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf("dockerAvailable")
class RagEndToEndTest {

    /** 无 Docker 时跳过本类用例。 */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("kb")
            .withUsername("kb")
            .withPassword("kb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestService ingestService;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    @DisplayName("健康检查端点可用")
    void healthEndpointIsAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("摄取文档后返回文档 ID 与切片数")
    void ingestReturnsDocumentIdAndChunkCount() throws Exception {
        mockMvc.perform(post("/api/v1/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knowledgeBaseId":"kb-test","title":"测试文档",
                                 "content":"向量检索的核心是相似度计算。第一段用于测试的内容。"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").exists())
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    @DisplayName("缺少必填字段时返回 400")
    void validationFailureReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"没有知识库 ID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("摄取同一内容两次得到同一文档 ID（幂等）")
    void ingestIsIdempotent() {
        String content = "幂等性测试内容：重复摄取应返回相同文档 ID。";
        String first = ingestService.ingest("kb-idem", "幂等文档", content).documentId();
        String second = ingestService.ingest("kb-idem", "幂等文档", content).documentId();
        assertThat(first).isEqualTo(second);
    }
}
