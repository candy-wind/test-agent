package vip.mate.agent.platform.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.config.AgentProperties;

/**
 * 对话与数据库模型配置之间的桥接。
 * 所有模型供应商/API Key 都来自「模型配置」页面写入的数据库记录，不再回退 application.yml。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderConfigResolver {

    private final ModelConfigService modelConfigService;

    /** 按供应商名称解析；名称不存在或配置不可用时返回 null。 */
    public AgentProperties.ProviderConfig resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return modelConfigService.resolveEffectiveProvider(name);
        } catch (Exception e) {
            log.warn("[model-resolve] 读取数据库模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    /** 按 CHAT 路由解析默认供应商；未配置路由时返回 null。 */
    public AgentProperties.ProviderConfig resolveDefaultChat() {
        try {
            return modelConfigService.resolveChatProviderFromRoute();
        } catch (Exception e) {
            log.warn("[model-resolve] 读取 CHAT 路由失败: {}", e.getMessage());
            return null;
        }
    }
}
