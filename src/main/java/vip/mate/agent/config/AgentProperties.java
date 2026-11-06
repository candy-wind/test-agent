package vip.mate.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置属性。
 * 模型供应商/API Key 已全部迁移到「模型配置」页面（数据库），
 * 这里只保留联网搜索等与模型无关的开关。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private SearchConfig search = new SearchConfig();

    /**
     * 数据库模型配置的传递对象，不作为 yml 配置项使用。
     */
    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        private String visionModel;
        private String reasonerModel;
    }

    @Data
    public static class SearchConfig {
        private boolean enabled = true;
        private int maxResults = 5;
    }
}
