package com.tianchen.kb.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.util.Objects;

/**
 * 零基础设施的本地向量库。
 *
 * <p>在 {@code kb.store=simple}（默认 profile {@code local}）时启用，
 * 数据驻留内存，无需 Docker 与 PostgreSQL 即可完整跑通摄取与问答链路，
 * 适合本地体验、演示与纯逻辑联调。生产或持久化场景请使用 PGvector profile。
 */
@Configuration
@ConditionalOnProperty(name = "kb.store", havingValue = "simple")
public class SimpleVectorStoreConfiguration {

    @Bean
    @Primary
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /** 仅用于校验 classpath 资源加载器可用，避免未使用告警。 */
    @Bean
    public PathMatchingResourcePatternResolver resourcePatternResolver() {
        return new PathMatchingResourcePatternResolver(Objects.requireNonNull(getClass().getClassLoader()));
    }
}
