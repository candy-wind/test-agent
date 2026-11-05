package vip.mate.agent.platform.model;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.agent.platform.common.ApiResponse;
import vip.mate.agent.platform.model.dto.ModelRequest;
import vip.mate.agent.platform.model.dto.ModelView;
import vip.mate.agent.platform.model.dto.ProviderRequest;
import vip.mate.agent.platform.model.dto.ProviderView;
import vip.mate.agent.platform.model.dto.RouteRequest;
import vip.mate.agent.platform.model.dto.RouteView;
import vip.mate.agent.platform.model.dto.TestResult;

import java.util.List;

/**
 * 平台模型配置接口（新增，不影响原有 /api/chat 等接口）。
 * 路径位于 /api/v1/platform 下，现有 AuthFilter 会自动做登录校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformModelController {

    private final ModelConfigService modelConfigService;

    // ==================== 供应商 ====================

    @GetMapping("/providers")
    public ApiResponse<List<ProviderView>> listProviders() {
        return ApiResponse.ok(modelConfigService.listProviders());
    }

    @PostMapping("/providers")
    public ApiResponse<ProviderView> createProvider(@Valid @RequestBody ProviderRequest request) {
        log.info("[platform] 新增供应商 name={} baseUrl={}", request.name(), request.baseUrl());
        return ApiResponse.ok(modelConfigService.createProvider(request));
    }

    @PutMapping("/providers/{id}")
    public ApiResponse<ProviderView> updateProvider(@PathVariable Long id,
                                                    @Valid @RequestBody ProviderRequest request) {
        log.info("[platform] 更新供应商 id={} name={}", id, request.name());
        return ApiResponse.ok(modelConfigService.updateProvider(id, request));
    }

    @DeleteMapping("/providers/{id}")
    public ApiResponse<Void> deleteProvider(@PathVariable Long id) {
        log.info("[platform] 删除供应商 id={}", id);
        modelConfigService.deleteProvider(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/providers/{id}/test")
    public ApiResponse<TestResult> testProvider(@PathVariable Long id) {
        return ApiResponse.ok(modelConfigService.testProvider(id));
    }

    // ==================== 模型 ====================

    @GetMapping("/providers/{id}/models")
    public ApiResponse<List<ModelView>> listModels(@PathVariable Long id) {
        return ApiResponse.ok(modelConfigService.listModels(id));
    }

    @PostMapping("/providers/{id}/models")
    public ApiResponse<ModelView> createModel(@PathVariable Long id,
                                              @Valid @RequestBody ModelRequest request) {
        log.info("[platform] 新增模型 providerId={} modelKey={}", id, request.modelKey());
        return ApiResponse.ok(modelConfigService.createModel(id, request));
    }

    @PutMapping("/models/{id}")
    public ApiResponse<ModelView> updateModel(@PathVariable Long id,
                                              @Valid @RequestBody ModelRequest request) {
        return ApiResponse.ok(modelConfigService.updateModel(id, request));
    }

    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable Long id) {
        modelConfigService.deleteModel(id);
        return ApiResponse.ok(null);
    }

    // ==================== 用途路由 ====================

    @GetMapping("/routes")
    public ApiResponse<List<RouteView>> listRoutes() {
        return ApiResponse.ok(modelConfigService.listRoutes());
    }

    @PutMapping("/routes/{purpose}")
    public ApiResponse<RouteView> setRoute(@PathVariable String purpose,
                                           @Valid @RequestBody RouteRequest request) {
        return ApiResponse.ok(modelConfigService.setRoute(purpose, request.modelId()));
    }
}
