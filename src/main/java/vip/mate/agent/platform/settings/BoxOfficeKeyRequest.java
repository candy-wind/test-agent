package vip.mate.agent.platform.settings;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存外部数据源 API Key 的请求
 */
public record BoxOfficeKeyRequest(@NotBlank(message = "API Key 不能为空") String apiKey) {
}
