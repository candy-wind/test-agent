package vip.mate.agent.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    List<AiModel> findByProviderIdOrderByIdAsc(Long providerId);

    Optional<AiModel> findByProviderIdAndModelKey(Long providerId, String modelKey);
}
