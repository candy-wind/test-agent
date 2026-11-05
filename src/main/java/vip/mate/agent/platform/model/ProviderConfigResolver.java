package vip.mate.agent.platform.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.config.AgentProperties;

/**
 * 对话与数据库模型配置之间的桥接。
 * 数据库不可用或未配置时返回 null，让 ChatServiceImpl 回退到 application.yml。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderConfigResolver {

    private final ModelConfigService modelConfigService;

    public AgentProperties.ProviderConfig resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return modelConfigService.resolveEffectiveProvider(name);
        } catch (Exception e) {
            log.warn("[model-resolve] 读取数据库模型配置失败，回退 application.yml: {}", e.getMessage());
            return null;
        }
    }
}
