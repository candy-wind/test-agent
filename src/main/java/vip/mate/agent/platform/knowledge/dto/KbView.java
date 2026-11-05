package vip.mate.agent.platform.knowledge.dto;

import java.time.Instant;

public record KbView(
        Long id,
        String name,
        String description,
        Integer chunkSize,
        Integer chunkOverlap,
        long documentCount,
        long chunkCount,
        String embeddingRoute,
        Instant createdAt
) {
}
