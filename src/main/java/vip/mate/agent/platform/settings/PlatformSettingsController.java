package vip.mate.agent.platform.settings;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.agent.platform.common.ApiResponse;
import vip.mate.agent.service.BoxOfficeService;

/**
 * 外部数据源配置接口（新增，不影响原有 /api/* 接口）。
 * 目前只包含票房数据源的 API Key。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/settings/boxoffice")
@RequiredArgsConstructor
public class PlatformSettingsController {

    private final BoxOfficeSettingsService settingsService;
    private final BoxOfficeService boxOfficeService;

    @GetMapping
    public ApiResponse<BoxOfficeKeyView> get() {
        return ApiResponse.ok(settingsService.view());
    }

    @PutMapping
    public ApiResponse<BoxOfficeKeyView> save(@Valid @RequestBody BoxOfficeKeyRequest request) {
        BoxOfficeKeyView view = settingsService.save(request.apiKey());
        refreshQuietly();
        return ApiResponse.ok(view);
    }

    @DeleteMapping
    public ApiResponse<BoxOfficeKeyView> clear() {
        settingsService.clear();
        refreshQuietly();
        return ApiResponse.ok(settingsService.view());
    }

    /** 保存后立即刷新缓存，让页面不用等下一个定时周期 */
    private void refreshQuietly() {
        try {
            boxOfficeService.refreshCache();
        } catch (Exception e) {
            log.warn("[settings] 保存后刷新票房缓存失败: {}", e.getMessage());
        }
    }
}
