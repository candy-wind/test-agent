package vip.mate.agent.platform.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.platform.common.CryptoService;
import vip.mate.agent.platform.workspace.WorkspaceService;

import java.time.Instant;

/**
 * 外部数据源配置：目前用于极数本源票房接口的 API Key。
 * 页面保存的值加密存库；未配置时回退环境变量 APIZERO_API_KEY / application.yml。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoxOfficeSettingsService {

    public static final String APIZERO_KEY = "boxoffice.apizero.api-key";

    private final SysConfigRepository configRepository;
    private final WorkspaceService workspaceService;
    private final CryptoService cryptoService;

    /** 兼容旧部署的环境变量/yml 配置，作为数据库未配置时的兜底 */
    @Value("${boxoffice.apizero.api-key:}")
    private String fallbackApiKey;

    @Transactional(readOnly = true)
    public BoxOfficeKeyView view() {
        String dbKey = readDatabaseKey();
        if (dbKey != null && !dbKey.isBlank()) {
            return new BoxOfficeKeyView(true, cryptoService.mask(dbKey), "database");
        }
        if (fallbackApiKey != null && !fallbackApiKey.isBlank()) {
            return new BoxOfficeKeyView(true, cryptoService.mask(fallbackApiKey.trim()), "environment");
        }
        return new BoxOfficeKeyView(false, "", "none");
    }

    /** 读取实际使用的 Key：页面配置优先，其次环境变量。 */
    @Transactional(readOnly = true)
    public String resolveApiKey() {
        String dbKey = readDatabaseKey();
        if (dbKey != null && !dbKey.isBlank()) {
            return dbKey;
        }
        return fallbackApiKey == null ? "" : fallbackApiKey.trim();
    }

    @Transactional
    public BoxOfficeKeyView save(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        String trimmed = apiKey.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException("API Key 只能包含可见 ASCII 字符，请检查是否粘贴了多余内容");
            }
        }

        Long workspaceId = workspaceService.defaultWorkspaceId();
        Instant now = Instant.now();
        SysConfig config = configRepository.findByWorkspaceIdAndConfigKey(workspaceId, APIZERO_KEY)
                .orElseGet(() -> SysConfig.builder()
                        .workspaceId(workspaceId)
                        .configKey(APIZERO_KEY)
                        .description("极数本源票房接口 API Key")
                        .createdAt(now)
                        .build());
        config.setConfigValue(cryptoService.encrypt(trimmed));
        config.setUpdatedAt(now);
        configRepository.save(config);

        log.info("[settings] 已更新极数本源 API Key（加密存储）: workspace={}", workspaceId);
        return new BoxOfficeKeyView(true, cryptoService.mask(trimmed), "database");
    }

    @Transactional
    public void clear() {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        configRepository.deleteByWorkspaceIdAndConfigKey(workspaceId, APIZERO_KEY);
        log.info("[settings] 已清除极数本源 API Key: workspace={}", workspaceId);
    }

    private String readDatabaseKey() {
        try {
            return configRepository
                    .findByWorkspaceIdAndConfigKey(workspaceService.defaultWorkspaceId(), APIZERO_KEY)
                    .map(SysConfig::getConfigValue)
                    .filter(value -> value != null && !value.isBlank())
                    .map(cryptoService::decrypt)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[settings] 读取极数本源 API Key 失败: {}", e.getMessage());
            return null;
        }
    }
}
