package vip.mate.agent.platform.knowledge.dto;

public record ChunkView(
        Long id,
        Integer seq,
        String content,
        Integer tokenCount
) {
}
