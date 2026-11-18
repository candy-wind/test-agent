package vip.mate.agent.platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "[A-Za-z0-9_.-]{2,64}", message = "用户名只能包含字母、数字、_ . -，长度 2-64")
        String username,

        @NotBlank(message = "初始口令不能为空")
        @Size(min = 8, max = 128, message = "初始口令至少 8 位")
        String password,

        String nickname,
        String role
) {
}
