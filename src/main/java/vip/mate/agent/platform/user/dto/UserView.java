package vip.mate.agent.platform.user.dto;

import vip.mate.agent.platform.workspace.SysUser;

import java.time.Instant;

/** 用户列表视图：永远不返回口令哈希。 */
public record UserView(Long id, String username, String nickname, String role,
                       String status, Instant createdAt, boolean self) {

    public static UserView from(SysUser user, Long currentUserId) {
        String role = user.getRole() == null || user.getRole().isBlank()
                ? "USER" : user.getRole().trim().toUpperCase();
        return new UserView(user.getId(), user.getUsername(), user.getNickname(), role,
                user.getStatus(), user.getCreatedAt(),
                currentUserId != null && currentUserId.equals(user.getId()));
    }
}
