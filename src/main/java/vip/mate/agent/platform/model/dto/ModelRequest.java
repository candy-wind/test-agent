package vip.mate.agent.platform.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 新增/更新模型请求
 */
public record ModelRequest(
        @NotBlank(message = "不能为空") String modelKey,
        String displayName,
        List<String> capabilities,
        Integer contextWindow,
        String defaultParams,
        Boolean enabled
) {
}
