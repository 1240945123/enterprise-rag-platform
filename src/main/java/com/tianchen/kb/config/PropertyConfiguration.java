package com.tianchen.kb.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用 kb.* 配置属性绑定。 */
@Configuration
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
public class PropertyConfiguration {
}
