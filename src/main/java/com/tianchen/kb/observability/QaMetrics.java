package com.tianchen.kb.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 问答质量埋点器。
 *
 * <p>把「答案是否落地」这一业务语义封装成显式方法，而不是让业务代码直接持有
 * {@code Counter}——避免出现「指标定义了却没有调用点」这种看似有监控、
 * 实际全是 0 的假象。
 *
 * <p>指标经 Micrometer 暴露到 {@code /actuator/prometheus}：
 * <ul>
 *   <li>{@code kb.qa.answers} —— 有引用支撑、真正调用大模型作答的次数</li>
 *   <li>{@code kb.qa.rejections} —— 因引用不足被拒答的次数</li>
 * </ul>
 * 两者之和即问答总请求量，比值即拒答率，是答案质量退化的第一手信号。
 */
public class QaMetrics {

    private final Counter groundedAnswers;
    private final Counter rejectedAnswers;

    public QaMetrics(MeterRegistry registry) {
        this.groundedAnswers = Counter.builder("kb.qa.answers")
                .description("有引用支撑并调用大模型作答的次数")
                .register(registry);
        this.rejectedAnswers = Counter.builder("kb.qa.rejections")
                .description("因引用不足被拒答的次数")
                .register(registry);
    }

    /** 记录一次成功作答（引用校验通过，已调用大模型）。 */
    public void recordGrounded() {
        groundedAnswers.increment();
    }

    /** 记录一次拒答（引用不足，未调用大模型）。 */
    public void recordRejected() {
        rejectedAnswers.increment();
    }
}