package vip.mate.agent.platform.knowledge;

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
 * 知识库
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kb_knowledge_base")
public class KbKnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private String name;

    private String description;

    /** 可选的固定 embedding 模型；为空时使用工作空间的 EMBEDDING 路由 */
    private Long embeddingModelId;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private String retrievalJson;

    private String status;

    private Instant createdAt;
}
