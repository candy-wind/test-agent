package vip.mate.agent.platform.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建/更新知识库
 */
public record KbRequest(
        @NotBlank(message = "知识库名称不能为空") String name,
        String description,
        Integer chunkSize,
        Integer chunkOverlap
) {
}
