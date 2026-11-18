package vip.mate.agent.platform.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.config.WorkspaceContext;
import vip.mate.agent.platform.common.AuditLogService;
import vip.mate.agent.platform.common.ForbiddenException;
import vip.mate.agent.platform.user.dto.CreateUserRequest;
import vip.mate.agent.platform.user.dto.ResetPasswordRequest;
import vip.mate.agent.platform.user.dto.UpdateUserRequest;
import vip.mate.agent.platform.user.dto.UserView;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceMember;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.WorkspaceService;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 多账号管理：仅 ADMIN 可操作。每次操作都重新查库校验角色，
 * 避免管理员被降级后旧会话仍持有管理权限。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final Set<String> ROLES = Set.of("ADMIN", "USER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final SysUserRepository userRepository;
    private final SysWorkspaceMemberRepository memberRepository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public List<UserView> list() {
        SysUser current = requireAdmin();
        return userRepository.findAllByOrderByIdAsc().stream()
                .map(user -> UserView.from(user, current.getId()))
                .toList();
    }

    @Transactional
    public UserView create(CreateUserRequest request) {
        SysUser current = requireAdmin();
        String username = normalizeUsername(request.username());
        validatePassword(request.password());
        String role = normalizeChoice(request.role(), ROLES, "USER", "角色");
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        String nickname = request.nickname() == null || request.nickname().isBlank()
                ? username : request.nickname().trim();

        SysUser user = userRepository.save(SysUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .nickname(nickname)
                .status("ACTIVE")
                .role(role)
                .createdAt(Instant.now())
                .build());
        ensureMember(user.getId(), workspaceService.currentWorkspaceId());
        auditLogService.record("USER_CREATED", "USER", username, "创建账号，角色=" + role);
        return UserView.from(user, current.getId());
    }

    @Transactional
    public UserView update(Long id, UpdateUserRequest request) {
        SysUser current = requireAdmin();
        SysUser target = requireUser(id);
        String role = request.role() == null
                ? roleOf(target) : normalizeChoice(request.role(), ROLES, roleOf(target), "角色");
        String status = request.status() == null
                ? target.getStatus() : normalizeChoice(request.status(), STATUSES, target.getStatus(), "状态");

        boolean losingAdmin = !"ADMIN".equals(role) || !"ACTIVE".equalsIgnoreCase(status);
        if (target.getId().equals(current.getId()) && losingAdmin) {
            throw new IllegalArgumentException("不能禁用或降级当前登录的管理员账号");
        }
        if (losingAdmin && countActiveAdminsExcluding(id) == 0) {
            throw new IllegalArgumentException("系统必须保留至少一个启用的管理员");
        }

        if (request.nickname() != null) {
            target.setNickname(request.nickname().trim());
        }
        target.setRole(role);
        target.setStatus(status);
        SysUser saved = userRepository.save(target);
        auditLogService.record("USER_UPDATED", "USER", saved.getUsername(),
                "角色=" + role + " 状态=" + status);
        return UserView.from(saved, current.getId());
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        requireAdmin();
        SysUser target = requireUser(id);
        validatePassword(request.password());
        target.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(target);
        auditLogService.record("USER_PASSWORD_RESET", "USER", target.getUsername(), "管理员重置口令");
    }

    /** 每次操作都从库里重新确认当前身份，避免依赖会话里缓存的角色。 */
    private SysUser requireAdmin() {
        WorkspaceContext.Identity identity = WorkspaceContext.get();
        if (identity == null || identity.userId() == null) {
            throw new ForbiddenException("未登录或会话已过期");
        }
        SysUser current = userRepository.findById(identity.userId())
                .orElseThrow(() -> new ForbiddenException("当前账号不存在"));
        if (!"ACTIVE".equalsIgnoreCase(current.getStatus()) || !"ADMIN".equals(roleOf(current))) {
            throw new ForbiddenException("只有管理员可以管理账号");
        }
        return current;
    }

    private SysUser requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("用户不存在: " + id));
    }

    private long countActiveAdminsExcluding(Long excludedId) {
        return userRepository.findAllByOrderByIdAsc().stream()
                .filter(user -> !user.getId().equals(excludedId))
                .filter(user -> "ACTIVE".equalsIgnoreCase(user.getStatus()))
                .filter(user -> "ADMIN".equals(roleOf(user)))
                .count();
    }

    private void ensureMember(Long userId, Long workspaceId) {
        if (workspaceId == null || memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            return;
        }
        memberRepository.save(SysWorkspaceMember.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .role("MEMBER")
                .createdAt(Instant.now())
                .build());
    }

    private String normalizeUsername(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z0-9_.-]{2,64}")) {
            throw new IllegalArgumentException("用户名只能包含字母、数字、_ . -，长度 2-64");
        }
        return value;
    }

    private void validatePassword(String raw) {
        if (raw == null || raw.length() < MIN_PASSWORD_LENGTH || raw.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("口令长度需为 8-128 位");
        }
    }

    private String normalizeChoice(String raw, Set<String> allowed, String fallback, String label) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(label + "只能是 " + allowed);
        }
        return value;
    }

    private String roleOf(SysUser user) {
        String role = user.getRole();
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }
}
