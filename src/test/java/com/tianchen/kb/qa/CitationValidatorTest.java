package com.tianchen.kb.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.tianchen.kb.domain.Citation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator(0.4, 1);

    private static Citation citation(double score, String snippet) {
        return new Citation("doc-1", "测试文档", 0, snippet, score);
    }

    @Test
    @DisplayName("低于阈值的引用被剔除")
    void lowScoreCitationsAreDropped() {
        List<Citation> input = List.of(citation(0.9, "有效内容"), citation(0.1, "低相关内容"));
        assertThat(validator.valid(input)).hasSize(1);
        assertThat(validator.valid(input).get(0).snippet()).isEqualTo("有效内容");
    }

    @Test
    @DisplayName("空片段即使分数高也不可用")
    void blankSnippetIsNotAcceptable() {
        List<Citation> input = List.of(citation(0.95, "   "));
        assertThat(validator.valid(input)).isEmpty();
    }

    @Test
    @DisplayName("没有有效引用时不落地，从而触发拒答")
    void noCitationsMeansNotGrounded() {
        assertThat(validator.grounded(List.of())).isFalse();
        assertThat(validator.grounded(List.of(citation(0.9, "内容")))).isTrue();
    }

    @Test
    @DisplayName("上下文格式化带编号，便于模型在答案中标注来源")
    void contextIsNumbered() {
        String context = validator.formatContext(
                List.of(citation(0.9, "第一段内容"), citation(0.8, "第二段内容")));
        assertThat(context).contains("[1] 来源：测试文档 #0");
        assertThat(context).contains("[2] 来源：测试文档 #0");
        assertThat(context).contains("第一段内容").contains("第二段内容");
    }

    @Test
    @DisplayName("最小引用数为 2 时，单条引用不足以落地")
    void minimumCitationsIsRespected() {
        CitationValidator strict = new CitationValidator(0.4, 2);
        assertThat(strict.grounded(List.of(citation(0.9, "内容")))).isFalse();
        assertThat(strict.grounded(List.of(citation(0.9, "内容"), citation(0.8, "另一段")))).isTrue();
    }

    @Test
    @DisplayName("空列表输入不抛异常")
    void nullInputIsSafe() {
        assertThat(validator.valid(null)).isEmpty();
        assertThat(validator.grounded(null)).isFalse();
    }
}
