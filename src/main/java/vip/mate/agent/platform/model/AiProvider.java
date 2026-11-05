package vip.mate.agent.platform.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 模型供应商（OpenAI 兼容协议，如 DeepSeek / Qwen / Ollama / vLLM）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_provider")
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private String name;

    /** OPENAI_COMPATIBLE / ANTHROPIC */
    private String type;

    private String baseUrl;

    private String apiKeyCipher;

    private String headersJson;

    private Boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;
}
