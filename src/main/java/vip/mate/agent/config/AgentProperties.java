package vip.mate.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String defaultProvider = "deepseek";
    private Map<String, ProviderConfig> providers;
    private SearchConfig search = new SearchConfig();

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
