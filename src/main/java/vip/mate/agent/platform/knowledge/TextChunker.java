package vip.mate.agent.platform.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于字符滑窗的文本切分，优先在段落、句子标点处断开。
 * 对中文和英文都适用，不依赖额外分词库。
 */
@Component
public class TextChunker {

    private static final String[] BOUNDARIES = {
            "\n\n", "\n", "。", "！", "？", "；", ". ", "; ", "，", ", "
    };

    public List<String> chunk(String text, int size, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int chunkSize = size <= 0 ? 600 : size;
        int chunkOverlap = overlap <= 0 ? 0 : Math.min(overlap, chunkSize / 2);

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        int length = normalized.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(length, start + chunkSize);
            if (end < length) {
                int lowerBound = start + Math.max(chunkSize / 2, 1);
                int boundary = findBoundary(normalized, lowerBound, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= length) {
                break;
            }
            int next = end - chunkOverlap;
            start = next > start ? next : end;
        }
        return chunks;
    }

    /**
     * 在 [from, to] 范围内寻找最靠后的自然断点，返回断点之后的位置。
     */
    private int findBoundary(String text, int from, int to) {
        int best = -1;
        for (String boundary : BOUNDARIES) {
            int index = text.lastIndexOf(boundary, to);
            if (index >= from) {
                best = Math.max(best, index + boundary.length());
            }
        }
        return best;
    }

    /**
     * 粗略估算 token 数：中文约 1.5 字符/token，英文约 4 字符/token，取中间值。
     */
    public int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(content.length() / 1.5));
    }
}
