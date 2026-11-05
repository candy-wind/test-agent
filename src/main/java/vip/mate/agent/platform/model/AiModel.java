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
 * 供应商提供的具体模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_model")
public class AiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long providerId;

    private String modelKey;

    private String displayName;

    /** JSON 数组，如 ["CHAT","VISION"] */
    private String capabilitiesJson;

    private Integer contextWindow;

    private String defaultParamsJson;

    private Boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;
}
