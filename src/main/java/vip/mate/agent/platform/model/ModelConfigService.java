package vip.mate.agent.platform.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.config.AgentProperties;
import vip.mate.agent.config.OutboundUrlGuard;
import vip.mate.agent.platform.common.AuditLogService;
import vip.mate.agent.platform.common.CryptoService;
import vip.mate.agent.platform.model.dto.ModelEndpoint;
import vip.mate.agent.platform.model.dto.ModelRequest;
import vip.mate.agent.platform.model.dto.ModelView;
import vip.mate.agent.platform.model.dto.ProviderRequest;
import vip.mate.agent.platform.model.dto.ProviderView;
import vip.mate.agent.platform.model.dto.RouteView;
import vip.mate.agent.platform.model.dto.TestResult;
import vip.mate.agent.platform.workspace.WorkspaceService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 模型配置服务：供应商、模型、用途路由的持久化管理。
 * 仅由新增的 /api/v1/platform 接口使用；原有 /api/chat 接口契约不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private static final Set<String> MODEL_CAPABILITIES =
            Set.of("CHAT", "VISION", "REASONER", "EMBEDDING", "RERANK");

    private static final Set<String> ROUTE_PURPOSES =
            Set.of("CHAT", "REASONER", "VISION", "EMBEDDING", "RERANK", "FAST");

    private final AiProviderRepository providerRepository;
    private final AiModelRepository modelRepository;
    private final AiModelRouteRepository routeRepository;
    private final WorkspaceService workspaceService;
    private final CryptoService cryptoService;
    private final OutboundUrlGuard outboundUrlGuard;
    private final AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ==================== 供应商 ====================

    @Transactional(readOnly = true)
    public List<ProviderView> listProviders() {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        return providerRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ProviderView createProvider(ProviderRequest request) {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        providerRepository.findByWorkspaceIdAndName(workspaceId, request.name())
                .ifPresent(p -> {
                    throw new IllegalArgumentException("供应商名称已存在: " + request.name());
                });

        String baseUrl = checkBaseUrl(request.baseUrl());
        Instant now = Instant.now();
        AiProvider provider = AiProvider.builder()
                .workspaceId(workspaceId)
                .name(request.name().trim())
                .type(normalizeType(request.type()))
                .baseUrl(baseUrl)
                .apiKeyCipher(cryptoService.encrypt(request.apiKey()))
                .headersJson(request.headers())
                .enabled(request.enabled() == null || request.enabled())
                .createdAt(now)
                .updatedAt(now)
                .build();
        AiProvider saved = providerRepository.save(provider);
        auditLogService.record("PROVIDER_CREATE", "AI_PROVIDER", String.valueOf(saved.getId()),
                "name=" + saved.getName() + ", baseUrl=" + saved.getBaseUrl());
        return toView(saved);
    }

    @Transactional
    public ProviderView updateProvider(Long id, ProviderRequest request) {
        AiProvider provider = requireProvider(id);
        Long workspaceId = workspaceService.defaultWorkspaceId();
        if (request.name() != null && !request.name().isBlank()) {
            final Long providerId = id;
            providerRepository.findByWorkspaceIdAndName(workspaceId, request.name().trim())
                    .filter(other -> !other.getId().equals(providerId))
                    .ifPresent(other -> {
                        throw new IllegalArgumentException("供应商名称已存在: " + request.name());
                    });
            provider.setName(request.name().trim());
        }
        if (request.type() != null && !request.type().isBlank()) {
            provider.setType(normalizeType(request.type()));
        }
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            provider.setBaseUrl(checkBaseUrl(request.baseUrl()));
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            provider.setApiKeyCipher(cryptoService.encrypt(request.apiKey()));
        }
        if (request.headers() != null) {
            provider.setHeadersJson(request.headers());
        }
        if (request.enabled() != null) {
            provider.setEnabled(request.enabled());
        }
        provider.setUpdatedAt(Instant.now());
        AiProvider saved = providerRepository.save(provider);
        auditLogService.record("PROVIDER_UPDATE", "AI_PROVIDER", String.valueOf(id),
                "name=" + saved.getName() + ", baseUrl=" + saved.getBaseUrl());
        return toView(saved);
    }

    @Transactional
    public void deleteProvider(Long id) {
        requireProvider(id);
        providerRepository.deleteById(id);
        auditLogService.record("PROVIDER_DELETE", "AI_PROVIDER", String.valueOf(id), "删除供应商");
    }

    @Transactional(readOnly = true)
    public TestResult testProvider(Long id) {
        AiProvider provider = requireProvider(id);
        String base = trimTrailingSlash(provider.getBaseUrl());
        outboundUrlGuard.assertAllowed(base);
        String url = base + "/models";
        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET();
            String apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - start) / 1_000_000;
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String message = ok ? "连接成功"
                    : "HTTP " + response.statusCode() + "：" + truncate(response.body(), 120);
            return new TestResult(ok, latency, response.statusCode(), message);
        } catch (Exception e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.warn("[model-test] 供应商连通性测试失败: provider={} url={}", provider.getName(), url, e);
            return new TestResult(false, latency, null,
                    "连接失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ==================== 模型 ====================

    @Transactional(readOnly = true)
    public List<ModelView> listModels(Long providerId) {
        requireProvider(providerId);
        return modelRepository.findByProviderIdOrderByIdAsc(providerId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ModelView createModel(Long providerId, ModelRequest request) {
        requireProvider(providerId);
        requireValidModelKey(request.modelKey());
        modelRepository.findByProviderIdAndModelKey(providerId, request.modelKey().trim())
                .ifPresent(m -> {
                    throw new IllegalArgumentException("模型已存在: " + request.modelKey());
                });

        List<String> capabilities = normalizeCapabilities(request.capabilities());
        Instant now = Instant.now();
        AiModel model = AiModel.builder()
                .providerId(providerId)
                .modelKey(request.modelKey().trim())
                .displayName(defaultText(request.displayName(), request.modelKey().trim()))
                .capabilitiesJson(writeCapabilities(capabilities))
                .contextWindow(request.contextWindow())
                .defaultParamsJson(request.defaultParams())
                .enabled(request.enabled() == null || request.enabled())
                .createdAt(now)
                .updatedAt(now)
                .build();
        AiModel saved = modelRepository.save(model);
        auditLogService.record("MODEL_CREATE", "AI_MODEL", String.valueOf(saved.getId()),
                "providerId=" + providerId + ", modelKey=" + saved.getModelKey());
        return toView(saved);
    }

    @Transactional
    public ModelView updateModel(Long id, ModelRequest request) {
        AiModel model = requireModel(id);
        requireValidModelKey(request.modelKey());
        modelRepository.findByProviderIdAndModelKey(model.getProviderId(), request.modelKey().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("模型已存在: " + request.modelKey());
                });
        model.setModelKey(request.modelKey().trim());
        if (request.displayName() != null && !request.displayName().isBlank()) {
            model.setDisplayName(request.displayName().trim());
        }
        if (request.capabilities() != null) {
            model.setCapabilitiesJson(writeCapabilities(normalizeCapabilities(request.capabilities())));
        }
        model.setContextWindow(request.contextWindow());
        if (request.defaultParams() != null) {
            model.setDefaultParamsJson(request.defaultParams());
        }
        if (request.enabled() != null) {
            model.setEnabled(request.enabled());
        }
        model.setUpdatedAt(Instant.now());
        AiModel saved = modelRepository.save(model);
        auditLogService.record("MODEL_UPDATE", "AI_MODEL", String.valueOf(id),
                "modelKey=" + saved.getModelKey());
        return toView(saved);
    }

    @Transactional
    public void deleteModel(Long id) {
        requireModel(id);
        modelRepository.deleteById(id);
        auditLogService.record("MODEL_DELETE", "AI_MODEL", String.valueOf(id), "删除模型");
    }

    // ==================== 用途路由 ====================

    @Transactional(readOnly = true)
    public List<RouteView> listRoutes() {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        return routeRepository.findByWorkspaceId(workspaceId).stream()
                .map(this::toRouteView)
                .toList();
    }

    @Transactional
    public RouteView setRoute(String purpose, Long modelId) {
        String normalizedPurpose = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        if (!ROUTE_PURPOSES.contains(normalizedPurpose)) {
            throw new IllegalArgumentException("不支持的用途: " + purpose);
        }
        AiModel model = requireModel(modelId);

        Long workspaceId = workspaceService.defaultWorkspaceId();
        AiModelRoute route = routeRepository.findByWorkspaceIdAndPurpose(workspaceId, normalizedPurpose)
                .orElseGet(() -> AiModelRoute.builder()
                        .workspaceId(workspaceId)
                        .purpose(normalizedPurpose)
                        .build());
        route.setModelId(model.getId());
        AiModelRoute saved = routeRepository.save(route);
        auditLogService.record("ROUTE_UPDATE", "AI_MODEL_ROUTE", normalizedPurpose,
                "purpose=" + normalizedPurpose + ", modelId=" + model.getId());
        return toRouteView(saved);
    }

    // ==================== 对话桥接 ====================

    /**
     * 按供应商名称读取数据库中的模型配置。
     * 返回 null 表示数据库中不可用（不存在、停用或未配置 CHAT 模型）。
     */
    @Transactional(readOnly = true)
    public AgentProperties.ProviderConfig resolveEffectiveProvider(String name) {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        AiProvider provider = providerRepository.findByWorkspaceIdAndName(workspaceId, name)
                .orElse(null);
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            return null;
        }

        List<AiModel> models = modelRepository.findByProviderIdOrderByIdAsc(provider.getId());
        String chatModel = pickModel(models, "CHAT");
        if (chatModel == null) {
            log.warn("[model-resolve] 供应商 {} 未配置 CHAT 模型", name);
            return null;
        }

        String baseUrl = checkBaseUrl(provider.getBaseUrl());
        AgentProperties.ProviderConfig config = new AgentProperties.ProviderConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey(cryptoService.decrypt(provider.getApiKeyCipher()));
        config.setChatModel(chatModel);
        config.setVisionModel(pickModel(models, "VISION"));
        config.setReasonerModel(pickModel(models, "REASONER"));
        return config;
    }

    /**
     * 按 CHAT 路由解析默认对话供应商，供未指定供应商的老接口使用。
     * 返回 null 表示尚未在「模型配置」页面配置可用的 CHAT 路由。
     */
    @Transactional(readOnly = true)
    public AgentProperties.ProviderConfig resolveChatProviderFromRoute() {
        ModelEndpoint endpoint = resolveEndpoint("CHAT");
        if (endpoint == null) {
            return null;
        }
        List<AiModel> models = modelRepository.findByProviderIdOrderByIdAsc(endpoint.providerId());
        AgentProperties.ProviderConfig config = new AgentProperties.ProviderConfig();
        config.setBaseUrl(checkBaseUrl(endpoint.baseUrl()));
        config.setApiKey(endpoint.apiKey());
        config.setChatModel(endpoint.modelKey());
        config.setVisionModel(pickModel(models, "VISION"));
        config.setReasonerModel(pickModel(models, "REASONER"));
        return config;
    }

    /**
     * 按用途（EMBEDDING / CHAT / ...）解析出可用的模型端点。
     * 未配置路由、模型或供应商被停用时返回 null，由调用方决定回退或报错。
     */
    @Transactional(readOnly = true)
    public ModelEndpoint resolveEndpoint(String purpose) {
        String normalized = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        Long workspaceId = workspaceService.defaultWorkspaceId();
        AiModelRoute route = routeRepository.findByWorkspaceIdAndPurpose(workspaceId, normalized)
                .orElse(null);
        if (route == null) {
            return null;
        }
        AiModel model = modelRepository.findById(route.getModelId()).orElse(null);
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) {
            return null;
        }
        AiProvider provider = providerRepository.findById(model.getProviderId()).orElse(null);
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            return null;
        }
        if (!workspaceId.equals(provider.getWorkspaceId())) {
            log.warn("[model-resolve] 路由指向了其它空间的供应商，已忽略: purpose={} providerId={}",
                    normalized, provider.getId());
            return null;
        }
        String baseUrl = checkBaseUrl(provider.getBaseUrl());
        return new ModelEndpoint(
                provider.getId(),
                provider.getName(),
                baseUrl,
                cryptoService.decrypt(provider.getApiKeyCipher()),
                model.getModelKey()
        );
    }

    // ==================== 内部方法 ====================

    /** 供应商必须属于当前空间，避免通过 id 越权读写其它空间的配置。 */
    private AiProvider requireProvider(Long id) {
        AiProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("供应商不存在: " + id));
        if (!workspaceService.defaultWorkspaceId().equals(provider.getWorkspaceId())) {
            throw new NoSuchElementException("供应商不存在: " + id);
        }
        return provider;
    }

    /** 模型必须挂在当前空间的供应商下。 */
    private AiModel requireModel(Long id) {
        AiModel model = modelRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("模型不存在: " + id));
        requireProvider(model.getProviderId());
        return model;
    }

    /** 保存/读取历史配置时都做一次出站地址校验，防止直连内网或元数据服务。 */
    private String checkBaseUrl(String rawUrl) {
        String base = trimTrailingSlash(rawUrl);
        outboundUrlGuard.assertAllowed(base);
        return base;
    }

    /**
     * 模型标识会原样传给 OpenAI 兼容接口的 model 字段，只允许可见 ASCII 字符，
     * 避免把“对话模型”之类的显示名称误填到这里。
     */
    private void requireValidModelKey(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        String trimmed = modelKey.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException(
                        "模型标识「" + trimmed + "」无效，请填写服务商真实模型名，例如 deepseek-chat、qwen-plus");
            }
        }
    }

    private String pickModel(List<AiModel> models, String capability) {
        return models.stream()
                .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
                .filter(model -> readCapabilities(model).contains(capability))
                .map(AiModel::getModelKey)
                .findFirst()
                .orElse(null);
    }

    private ProviderView toView(AiProvider provider) {
        String masked = "";
        try {
            masked = cryptoService.mask(cryptoService.decrypt(provider.getApiKeyCipher()));
        } catch (Exception e) {
            masked = "****";
        }
        return new ProviderView(
                provider.getId(),
                provider.getWorkspaceId(),
                provider.getName(),
                provider.getType(),
                provider.getBaseUrl(),
                masked,
                provider.getHeadersJson(),
                provider.getEnabled(),
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }

    private ModelView toView(AiModel model) {
        return new ModelView(
                model.getId(),
                model.getProviderId(),
                model.getModelKey(),
                model.getDisplayName(),
                readCapabilities(model),
                model.getContextWindow(),
                model.getEnabled());
    }

    private RouteView toRouteView(AiModelRoute route) {
        AiModel model = modelRepository.findById(route.getModelId()).orElse(null);
        AiProvider provider = model == null ? null
                : providerRepository.findById(model.getProviderId()).orElse(null);
        return new RouteView(
                route.getId(),
                route.getPurpose(),
                route.getModelId(),
                provider == null ? null : provider.getId(),
                provider == null ? null : provider.getName(),
                model == null ? null : model.getModelKey());
    }

    private List<String> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of("CHAT");
        }
        List<String> result = new ArrayList<>();
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                continue;
            }
            String normalized = capability.trim().toUpperCase(Locale.ROOT);
            if (!MODEL_CAPABILITIES.contains(normalized)) {
                throw new IllegalArgumentException("不支持的能力: " + capability);
            }
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result.isEmpty() ? List.of("CHAT") : result;
    }

    private String writeCapabilities(List<String> capabilities) {
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (Exception e) {
            throw new IllegalStateException("序列化模型能力失败", e);
        }
    }

    private List<String> readCapabilities(AiModel model) {
        String json = model.getCapabilitiesJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("[model] 模型能力 JSON 解析失败: modelId={}", model.getId());
            return List.of();
        }
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank()
                ? "OPENAI_COMPATIBLE"
                : type.trim().toUpperCase(Locale.ROOT);
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
