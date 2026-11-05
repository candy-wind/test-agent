package vip.mate.agent.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    List<AiProvider> findByWorkspaceIdOrderByIdAsc(Long workspaceId);

    Optional<AiProvider> findByWorkspaceIdAndName(Long workspaceId, String name);
}
