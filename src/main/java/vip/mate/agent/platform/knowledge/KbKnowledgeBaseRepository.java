package vip.mate.agent.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbKnowledgeBaseRepository extends JpaRepository<KbKnowledgeBase, Long> {

    List<KbKnowledgeBase> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    Optional<KbKnowledgeBase> findByIdAndWorkspaceId(Long id, Long workspaceId);
}
