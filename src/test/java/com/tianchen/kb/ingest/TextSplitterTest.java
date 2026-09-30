package com.tianchen.kb.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextSplitterTest {

    @Test
    @DisplayName("短文本不切分")
    void shortTextStaysWhole() {
        TextSplitter splitter = new TextSplitter(100, 20);
        assertThat(splitter.split("很短的一段话。")).hasSize(1);
    }

    @Test
    @DisplayName("空白或空文本返回空列表")
    void blankTextReturnsEmpty() {
        TextSplitter splitter = new TextSplitter(100, 20);
        assertThat(splitter.split("   ")).isEmpty();
        assertThat(splitter.split(null)).isEmpty();
    }

    @Test
    @DisplayName("每个切片长度不超过 chunkSize")
    void everyChunkRespectsSizeLimit() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("这是第 ").append(i).append(" 句用于测试切分器的内容。");
        }
        TextSplitter splitter = new TextSplitter(300, 60);
        assertThat(splitter.split(text.toString())).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(300));
    }

    @Test
    @DisplayName("内容不丢失：切片拼接后包含全部原始句子")
    void noContentIsLost() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("SENTINEL").append(i).append("。");
        }
        TextSplitter splitter = new TextSplitter(120, 30);
        String joined = String.join("", splitter.split(text.toString()));
        for (int i = 0; i < 50; i++) {
            assertThat(joined).contains("SENTINEL" + i);
        }
    }

    @Test
    @DisplayName("overlap 为 0 时内容不重复：标记句只出现一次")
    void zeroOverlapKeepsEveryFragmentExactlyOnce() {
        String text = "MARKER_A。MARKER_B。MARKER_C。MARKER_D。";
        TextSplitter splitter = new TextSplitter(12, 0);
        String joined = String.join("", splitter.split(text));
        for (String marker : new String[] {"MARKER_A", "MARKER_B", "MARKER_C", "MARKER_D"}) {
            assertThat(countOccurrences(joined, marker)).isEqualTo(1);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int found = haystack.indexOf(needle, fromIndex);
            if (found < 0) {
                return count;
            }
            count++;
            fromIndex = found + needle.length();
        }
    }

    /**
     * 回归测试：长文本若不含空行，早期实现会把整段原样传回自身形成无限递归，
     * 在到达 hardSplit 兜底之前就抛 StackOverflowError。
     * 这里刻意构造「单一超长段落 + 无任何分隔符」的输入。
     */
    @Test
    @DisplayName("回归：无空行、无标点的超长文本不栈溢出且有兜底切分")
    void longTextWithoutDelimitersDoesNotOverflow() {
        String text = "A".repeat(5000);
        TextSplitter splitter = new TextSplitter(300, 60);
        var chunks = splitter.split(text);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(300));
    }

    /** 回归：只有换行没有空行的长文本（典型的一段式粘贴内容）。 */
    @Test
    @DisplayName("回归：只有换行的超长文本能正常切分且不丢内容")
    void longTextWithSingleNewlinesIsHandled() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("line-").append(i).append("\n");
        }
        TextSplitter splitter = new TextSplitter(300, 60);
        var chunks = splitter.split(text.toString());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(300));
        String joined = String.join("", chunks);
        assertThat(joined).contains("line-0").contains("line-399");
    }

    /** 中文长句（只有句号、无空行）也应正常切分。 */
    @Test
    @DisplayName("中文长句仅用句号分隔时能正常切分")
    void chineseSentencesWithoutBlankLinesAreHandled() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            text.append("这是第").append(i).append("个用于测试的中文句子。");
        }
        TextSplitter splitter = new TextSplitter(280, 50);
        var chunks = splitter.split(text.toString());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(280));
        String joined = String.join("", chunks);
        assertThat(joined).contains("这是第0个用于测试的中文句子").contains("这是第119个用于测试的中文句子");
    }

    @Test
    @DisplayName("非法参数被拒绝")
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> new TextSplitter(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextSplitter(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextSplitter(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
