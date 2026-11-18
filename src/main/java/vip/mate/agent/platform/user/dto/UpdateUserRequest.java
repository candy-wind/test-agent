package vip.mate.agent.platform.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 64, message = "昵称最多 64 字") String nickname,
        String role,
        String status
) {
}
