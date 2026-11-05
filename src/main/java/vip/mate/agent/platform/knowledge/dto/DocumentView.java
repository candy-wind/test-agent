package vip.mate.agent.platform.knowledge.dto;

import java.time.Instant;

public record DocumentView(
        Long id,
        Long kbId,
        String name,
        String mime,
        Long size,
        String status,
        String errorMsg,
        long chunkCount,
        Instant createdAt
) {
}
