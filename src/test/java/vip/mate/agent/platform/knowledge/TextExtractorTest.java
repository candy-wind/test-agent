package vip.mate.agent.platform.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void extractsUtf8Text() {
        String text = extractor.extract("policy.md", "text/markdown",
                "# 退款政策\n\n7 天内可申请退款。".getBytes(StandardCharsets.UTF_8));
        assertThat(text).contains("退款政策").contains("7 天内可申请退款");
    }

    @Test
    void stripsHtmlTags() {
        String html = "<html><body><h1>标题</h1><script>alert(1)</script><p>正文</p></body></html>";
        String text = extractor.extract("page.html", "text/html", html.getBytes(StandardCharsets.UTF_8));
        assertThat(text).contains("标题").contains("正文");
        assertThat(text).doesNotContain("<h1>").doesNotContain("alert(1)");
    }

    @Test
    void rejectsUnsupportedBinary() {
        assertThatThrownBy(() -> extractor.extract("image.png", "image/png", new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暂不支持");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> extractor.extract("empty.txt", "text/plain", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
    }
}
