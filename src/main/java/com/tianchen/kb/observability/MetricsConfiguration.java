package com.tianchen.kb.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义业务指标，把「检索是否命中」「答案是否落地」这类质量指标与延迟指标一起观测，
 * 避免只看 QPS 而看不到答案质量。计数逻辑见 {@link QaMetrics}。
 */
@Configuration
public class MetricsConfiguration {

    /**
     * 初始化期主动注册一次计数器，保证服务启动后指标即以 0 出现，
     * 而不是等到第一次问答才出现在 /actuator/prometheus 里——
     * 仪表盘不会因为「尚无流量」而报 "no data"。
     */
    @Bean
    public QaMetrics qaMetrics(MeterRegistry registry) {
        return new QaMetrics(registry);
    }
}