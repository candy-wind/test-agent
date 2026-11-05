package vip.mate.agent.platform.model.dto;

public record RouteView(
        Long id,
        String purpose,
        Long modelId,
        Long providerId,
        String providerName,
        String modelKey
) {
}
