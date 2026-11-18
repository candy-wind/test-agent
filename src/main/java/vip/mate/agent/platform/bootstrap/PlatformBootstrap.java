package vip.mate.agent.platform.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.config.AuthProperties;
import vip.mate.agent.platform.model.AiProviderRepository;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspace;
import vip.mate.agent.platform.workspace.SysWorkspaceMember;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceRepository;

import java.time.Instant;
import java.util.Locale;

/**
 * 首次启动初始化：
 * 1. 创建/同步引导管理员（ADMIN，口令 BCrypt 存 sys_user）
 * 2. 创建默认工作空间与 OWNER 成员关系
 * 3. 按 AUTH_INITIAL_USERS 批量创建账号（只创建不存在的用户名）
 * <p>
 * 口令策略：显式配置 AUTH_PASSWORD / AUTH_PASSWORD_HASH 时才在每次启动同步；
 * 未配置且账号已存在时**保留现有口令**，避免重启后其他人无法登录；
 * 未配置且账号不存在时使用固定默认口令 123456（不生成随机口令）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformBootstrap implements ApplicationRunner {

    private static final String USERNAME_PATTERN = "[A-Za-z0-9_.-]{2,64}";

    /** 未配置环境变量口令时，新库中管理员的固定默认口令。 */
    static final String DEFAULT_ADMIN_PASSWORD = "123456";

    private final SysUserRepository userRepository;
    private final SysWorkspaceRepository workspaceRepository;
    private final SysWorkspaceMemberRepository memberRepository;
    private final AiProviderRepository providerRepository;
    private final AuthProperties authProperties;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String configuredHash = resolveConfiguredPasswordHash();
        String username = authProperties.getUsername();

        SysUser admin = userRepository.findByUsername(username)
                .map(existing -> syncAdmin(existing, configuredHash))
                .orElseGet(() -> createAdmin(username,
                        configuredHash != null ? configuredHash
                                : passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD)));

        SysWorkspace workspace = workspaceRepository.findAllByOrderByIdAsc().stream()
                .findFirst()
                .orElseGet(() -> createWorkspace(admin));

        ensureMember(workspace.getId(), admin.getId(), "OWNER");
        int created = createInitialUsers(workspace.getId());

        log.info("[bootstrap] 初始化完成: admin={} workspace={}({}) initialUsersCreated={} providerCount={}",
                username, workspace.getName(), workspace.getId(), created, providerRepository.count());
    }

    /** 只在显式配置口令时覆盖；未配置时返回 null，表示保留库中已有哈希。 */
    private String resolveConfiguredPasswordHash() {
        String hash = authProperties.getPasswordHash();
        if (hash != null && !hash.isBlank()) {
            String trimmed = hash.trim();
            if (!trimmed.startsWith("$2")) {
                throw new IllegalStateException("AUTH_PASSWORD_HASH 不是有效的 BCrypt 哈希，"
                        + "请改用 AUTH_PASSWORD=<明文> 或生成 BCrypt 哈希后再配置");
            }
            return trimmed;
        }
        String raw = authProperties.getPassword();
        if (raw != null && !raw.isBlank()) {
            return passwordEncoder.encode(raw);
        }
        return null;
    }

    private SysUser syncAdmin(SysUser existing, String configuredHash) {
        if (configuredHash != null) {
            existing.setPasswordHash(configuredHash);
        }
        existing.setStatus("ACTIVE");
        existing.setRole("ADMIN");
        return userRepository.save(existing);
    }

    /** 解析 AUTH_INITIAL_USERS：user:password[:role]，多个账号用逗号分隔。 */
    private int createInitialUsers(Long workspaceId) {
        String raw = authProperties.getInitialUsers();
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int created = 0;
        for (String entry : raw.split(",")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] parts = item.split(":", 3);
            if (parts.length < 2 || !parts[0].trim().matches(USERNAME_PATTERN) || parts[1].length() < 8) {
                log.warn("[bootstrap] 跳过无效的 AUTH_INITIAL_USERS 条目（需 user:password[:role]，口令至少 8 位）");
                continue;
            }
            String name = parts[0].trim();
            if (userRepository.findByUsername(name).isPresent()) {
                continue;
            }
            String role = parts.length == 3 && "ADMIN".equalsIgnoreCase(parts[2].trim()) ? "ADMIN" : "USER";
            SysUser user = userRepository.save(SysUser.builder()
                    .username(name)
                    .passwordHash(passwordEncoder.encode(parts[1]))
                    .nickname(name)
                    .status("ACTIVE")
                    .role(role)
                    .createdAt(Instant.now())
                    .build());
            ensureMember(workspaceId, user.getId(), "MEMBER");
            created++;
            log.info("[bootstrap] 按 AUTH_INITIAL_USERS 创建账号: {} role={}", name, role.toUpperCase(Locale.ROOT));
        }
        return created;
    }

    private void ensureMember(Long workspaceId, Long userId, String role) {
        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            memberRepository.save(SysWorkspaceMember.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .role(role)
                    .createdAt(Instant.now())
                    .build());
        }
    }

    private SysUser createAdmin(String username, String passwordHash) {
        log.info("[bootstrap] 创建管理员: {}", username);
        log.warn("[bootstrap] 未配置 AUTH_PASSWORD/AUTH_PASSWORD_HASH，管理员使用默认口令 {}，"
                + "请登录后在「用户管理」中修改", DEFAULT_ADMIN_PASSWORD);
        return userRepository.save(SysUser.builder()
                .username(username)
                .passwordHash(passwordHash)
                .nickname("管理员")
                .status("ACTIVE")
                .role("ADMIN")
                .createdAt(Instant.now())
                .build());
    }

    private SysWorkspace createWorkspace(SysUser owner) {
        log.info("[bootstrap] 创建默认工作空间");
        return workspaceRepository.save(SysWorkspace.builder()
                .name("默认空间")
                .slug("default")
                .ownerId(owner.getId())
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());
    }
}
