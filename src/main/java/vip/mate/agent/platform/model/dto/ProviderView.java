package vip.mate.agent.platform.model.dto;

import java.time.Instant;

/**
 * 供应商视图，API Key 只返回掩码
 */
public record ProviderView(
        Long id,
        Long workspaceId,
        String name,
        String type,
        String baseUrl,
        String apiKeyMasked,
        String headers,
        Boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
