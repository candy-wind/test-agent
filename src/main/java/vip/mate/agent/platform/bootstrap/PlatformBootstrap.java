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
import vip.mate.agent.config.AgentProperties;
import vip.mate.agent.platform.common.CryptoService;
import vip.mate.agent.platform.model.AiModel;
import vip.mate.agent.platform.model.AiModelRepository;
import vip.mate.agent.platform.model.AiModelRoute;
import vip.mate.agent.platform.model.AiModelRouteRepository;
import vip.mate.agent.platform.model.AiProvider;
import vip.mate.agent.platform.model.AiProviderRepository;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspace;
import vip.mate.agent.platform.workspace.SysWorkspaceMember;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 首次启动初始化：
 * 1. 创建默认管理员（复用 auth.username/password）
 * 2. 创建默认工作空间与成员关系
 * 3. 把 application.yml 中的 agent.providers 作为种子写入数据库（跳过占位 Key）
 * <p>
 * 不改变任何现有接口；初始化完成后对话默认读取数据库配置，读不到时回退 yml。
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
    private final AiModelRepository modelRepository;
    private final AiModelRouteRepository routeRepository;
    private final CryptoService cryptoService;
    private final AgentProperties agentProperties;

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

        if (providerRepository.count() == 0) {
            seedProviders(workspace.getId());
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

    private void seedProviders(Long workspaceId) {
        Map<String, AgentProperties.ProviderConfig> providers = agentProperties.getProviders();
        if (providers == null || providers.isEmpty()) {
            return;
        }
        providers.forEach((name, config) -> {
            if (!isUsable(config)) {
                log.info("[bootstrap] 跳过供应商 {}：application.yml 中未配置有效 api-key", name);
                return;
            }
            Instant now = Instant.now();
            AiProvider provider = providerRepository.save(AiProvider.builder()
                    .workspaceId(workspaceId)
                    .name(name)
                    .type("OPENAI_COMPATIBLE")
                    .baseUrl(trimTrailingSlash(config.getBaseUrl()))
                    .apiKeyCipher(cryptoService.encrypt(config.getApiKey()))
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            List<AiModel> models = new ArrayList<>();
            models.add(saveModel(provider.getId(), config.getChatModel(), "CHAT"));
            models.add(saveModel(provider.getId(), config.getVisionModel(), "VISION"));
            models.add(saveModel(provider.getId(), config.getReasonerModel(), "REASONER"));
            models.removeIf(java.util.Objects::isNull);

            if (!models.isEmpty() && routeRepository.count() == 0) {
                routeRepository.save(AiModelRoute.builder()
                        .workspaceId(workspaceId)
                        .purpose("CHAT")
                        .modelId(models.get(0).getId())
                        .build());
            }
            log.info("[bootstrap] 种子供应商 {} 完成，模型数={}", name, models.size());
        });
    }

    private AiModel saveModel(Long providerId, String modelKey, String capability) {
        if (modelKey == null || modelKey.isBlank()) {
            return null;
        }
        if (modelRepository.findByProviderIdAndModelKey(providerId, modelKey).isPresent()) {
            return null;
        }
        Instant now = Instant.now();
        return modelRepository.save(AiModel.builder()
                .providerId(providerId)
                .modelKey(modelKey.trim())
                .displayName(modelKey.trim())
                .capabilitiesJson("[\"" + capability + "\"]")
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private boolean isUsable(AgentProperties.ProviderConfig config) {
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            return false;
        }
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String lower = apiKey.toLowerCase();
        return !lower.contains("your") && !apiKey.contains("你的");
    }

    private String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
