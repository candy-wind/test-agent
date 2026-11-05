package vip.mate.agent.platform.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.config.AgentProperties;
import vip.mate.agent.platform.common.CryptoService;
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

        Instant now = Instant.now();
        AiProvider provider = AiProvider.builder()
                .workspaceId(workspaceId)
                .name(request.name().trim())
                .type(normalizeType(request.type()))
                .baseUrl(trimTrailingSlash(request.baseUrl()))
                .apiKeyCipher(cryptoService.encrypt(request.apiKey()))
                .headersJson(request.headers())
                .enabled(request.enabled() == null || request.enabled())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public ProviderView updateProvider(Long id, ProviderRequest request) {
        AiProvider provider = requireProvider(id);
        Long workspaceId = provider.getWorkspaceId();
        providerRepository.findByWorkspaceIdAndName(workspaceId, request.name())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("供应商名称已存在: " + request.name());
                });

        provider.setName(request.name().trim());
        if (request.type() != null && !request.type().isBlank()) {
            provider.setType(normalizeType(request.type()));
        }
        provider.setBaseUrl(trimTrailingSlash(request.baseUrl()));
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
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public void deleteProvider(Long id) {
        requireProvider(id);
        providerRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public TestResult testProvider(Long id) {
        AiProvider provider = requireProvider(id);
        String url = trimTrailingSlash(provider.getBaseUrl()) + "/models";
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
            String message = ok ? "连接成功" : truncate(response.body(), 300);
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
        return toView(modelRepository.save(model));
    }

    @Transactional
    public ModelView updateModel(Long id, ModelRequest request) {
        AiModel model = modelRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("模型不存在: " + id));

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
        return toView(modelRepository.save(model));
    }

    @Transactional
    public void deleteModel(Long id) {
        if (!modelRepository.existsById(id)) {
            throw new NoSuchElementException("模型不存在: " + id);
        }
        modelRepository.deleteById(id);
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
        AiModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new NoSuchElementException("模型不存在: " + modelId));

        Long workspaceId = workspaceService.defaultWorkspaceId();
        AiModelRoute route = routeRepository.findByWorkspaceIdAndPurpose(workspaceId, normalizedPurpose)
                .orElseGet(() -> AiModelRoute.builder()
                        .workspaceId(workspaceId)
                        .purpose(normalizedPurpose)
                        .build());
        route.setModelId(model.getId());
        return toRouteView(routeRepository.save(route));
    }

    // ==================== 对话桥接 ====================

    /**
     * 为现有 ChatServiceImpl 提供数据库中的供应商配置。
     * 返回 null 表示数据库中不可用，由调用方回退 application.yml。
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
            log.warn("[model-resolve] 供应商 {} 未配置 CHAT 模型，回退 application.yml", name);
            return null;
        }

        AgentProperties.ProviderConfig config = new AgentProperties.ProviderConfig();
        config.setBaseUrl(provider.getBaseUrl());
        config.setApiKey(cryptoService.decrypt(provider.getApiKeyCipher()));
        config.setChatModel(chatModel);
        config.setVisionModel(pickModel(models, "VISION"));
        config.setReasonerModel(pickModel(models, "REASONER"));
        return config;
    }

    // ==================== 内部方法 ====================

    private AiProvider requireProvider(Long id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("供应商不存在: " + id));
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
