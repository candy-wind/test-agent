package vip.mate.agent.platform.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.platform.model.AiProviderRepository;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspace;
import vip.mate.agent.platform.workspace.SysWorkspaceMember;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceRepository;

import java.time.Instant;

/**
 * 首次启动初始化：
 * 1. 创建默认管理员（复用 auth.username/password）
 * 2. 创建默认工作空间与成员关系
 * <p>
 * 模型供应商与 API Key 不再从 application.yml 读取，统一在「模型配置」页面维护。
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

    @Value("${auth.username:admin}")
    private String adminUsername;

    @Value("${auth.password:admin123}")
    private String adminPassword;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SysUser admin = userRepository.findByUsername(adminUsername)
                .orElseGet(this::createAdmin);

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

        log.info("[bootstrap] 初始化完成: workspace={}({}) providerCount={}",
                workspace.getName(), workspace.getId(), providerRepository.count());
    }

    private SysUser createAdmin() {
        log.info("[bootstrap] 创建默认管理员: {}", adminUsername);
        return userRepository.save(SysUser.builder()
                .username(adminUsername)
                .passwordHash(passwordEncoder.encode(adminPassword))
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
