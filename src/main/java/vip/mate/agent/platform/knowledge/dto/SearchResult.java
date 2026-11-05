package vip.mate.agent.platform.knowledge.dto;

/**
 * 检索命中的文本块。score 为余弦相似度，越大越相关（0~1）。
 */
public record SearchResult(
        Long chunkId,
        Long documentId,
        String documentName,
        Integer seq,
        String content,
        Double score
) {
}
