package vip.mate.agent.platform.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank(message = "检索内容不能为空") String query,
        Integer topK
) {
}
