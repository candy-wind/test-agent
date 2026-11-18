package vip.mate.agent.platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "新口令不能为空")
        @Size(min = 8, max = 128, message = "新口令至少 8 位")
        String password
) {
}
