package vip.mate.agent.platform.model.dto;

import java.util.List;

public record ModelView(
        Long id,
        Long providerId,
        String modelKey,
        String displayName,
        List<String> capabilities,
        Integer contextWindow,
        Boolean enabled
) {
}
