package vip.mate.agent.platform.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void shortTextProducesSingleChunk() {
        List<String> chunks = chunker.chunk("这是一段很短的文本。", 600, 80);
        assertThat(chunks).containsExactly("这是一段很短的文本。");
    }

    @Test
    void blankTextProducesNothing() {
        assertThat(chunker.chunk("   ", 600, 80)).isEmpty();
        assertThat(chunker.chunk(null, 600, 80)).isEmpty();
    }

    @Test
    void longTextSplitsIntoBoundedChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("第").append(i).append("段：退款申请需要在购买后七天内提交，审核通过后原路退回。\n\n");
        }
        List<String> chunks = chunker.chunk(sb.toString(), 200, 30);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).isNotBlank();
            assertThat(chunk.length()).isLessThanOrEqualTo(201);
        });
        assertThat(String.join("", chunks)).contains("第0段").contains("第39段");
    }

    @Test
    void overlapKeepsContextBetweenChunks() {
        String text = "甲".repeat(100) + "。" + "乙".repeat(100) + "。" + "丙".repeat(100) + "。";
        List<String> chunks = chunker.chunk(text, 120, 40);
        assertThat(chunks.size()).isGreaterThan(1);
        String firstTail = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 10));
        assertThat(chunks.get(1)).contains(firstTail);
    }

    @Test
    void estimatesTokens() {
        assertThat(chunker.estimateTokens("")).isZero();
        assertThat(chunker.estimateTokens("123456789")).isEqualTo(6);
    }
}
