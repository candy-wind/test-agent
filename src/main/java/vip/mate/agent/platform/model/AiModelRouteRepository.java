package vip.mate.agent.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelRouteRepository extends JpaRepository<AiModelRoute, Long> {

    List<AiModelRoute> findByWorkspaceId(Long workspaceId);

    Optional<AiModelRoute> findByWorkspaceIdAndPurpose(Long workspaceId, String purpose);
}
