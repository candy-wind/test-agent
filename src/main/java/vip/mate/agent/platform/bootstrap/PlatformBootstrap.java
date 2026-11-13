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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * 首次启动初始化：
 * 1. 按环境变量创建/同步管理员（口令用 BCrypt 存 sys_user）
 * 2. 创建默认工作空间与 OWNER 成员关系
 * <p>
 * 不再存在任何内置弱口令：未配置口令时生成一次性随机口令并打印在启动日志中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformBootstrap implements ApplicationRunner {

    private final SysUserRepository userRepository;
    private final SysWorkspaceRepository workspaceRepository;
    private final SysWorkspaceMemberRepository memberRepository;
    private final AiProviderRepository providerRepository;
    private final AuthProperties authProperties;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String passwordHash = resolvePasswordHash();
        String username = authProperties.getUsername();
        SysUser admin = userRepository.findByUsername(username)
                .map(existing -> {
                    existing.setPasswordHash(passwordHash);
                    existing.setStatus("ACTIVE");
                    return userRepository.save(existing);
                })
                .orElseGet(() -> createAdmin(username, passwordHash));

        SysWorkspace workspace = workspaceRepository.findAllByOrderByIdAsc().stream()
                .findFirst()
                .orElseGet(() -> createWorkspace(admin));

        if (!memberRepository.existsByWorkspaceIdAndUserId(workspace.getId(), admin.getId())) {
            memberRepository.save(SysWorkspaceMember.builder()
                    .workspaceId(workspace.getId())
                    .userId(admin.getId())
                    .role("OWNER")
                    .createdAt(Instant.now())
                    .build());
        }

        log.info("[bootstrap] 初始化完成: user={} workspace={}({}) providerCount={}",
                username, workspace.getName(), workspace.getId(), providerRepository.count());
    }

    /**
     * 口令来源优先级：AUTH_PASSWORD_HASH（BCrypt）> AUTH_PASSWORD（明文）> 一次性随机口令。
     * 生产环境应在 .env 中固定其中一个；随机口令每次重启都会变化。
     */
    private String resolvePasswordHash() {
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
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        log.warn("\n==================== 管理员临时口令 ====================\n"
                        + "  未配置 AUTH_PASSWORD / AUTH_PASSWORD_HASH，已生成一次性口令：\n"
                        + "  账号: {}\n  口令: {}\n"
                        + "  请设置环境变量固定口令；应用重启后该口令会重新生成。\n"
                        + "=====================================================",
                authProperties.getUsername(), generated);
        return passwordEncoder.encode(generated);
    }

    private SysUser createAdmin(String username, String passwordHash) {
        log.info("[bootstrap] 创建管理员: {}", username);
        return userRepository.save(SysUser.builder()
                .username(username)
                .passwordHash(passwordHash)
                .nickname("管理员")
                .status("ACTIVE")
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
