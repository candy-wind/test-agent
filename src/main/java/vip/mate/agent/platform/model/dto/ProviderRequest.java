package vip.mate.agent.platform.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新增/更新供应商请求
 */
public record ProviderRequest(
        @NotBlank(message = "不能为空") String name,
        String type,
        @NotBlank(message = "不能为空") String baseUrl,
        String apiKey,
        String headers,
        Boolean enabled
) {
}
