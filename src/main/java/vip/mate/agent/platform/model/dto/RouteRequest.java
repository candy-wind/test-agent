package vip.mate.agent.platform.model.dto;

import jakarta.validation.constraints.NotNull;

public record RouteRequest(@NotNull(message = "不能为空") Long modelId) {
}
