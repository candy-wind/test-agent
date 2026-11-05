package vip.mate.agent.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    List<KbDocument> findByKbIdOrderByIdDesc(Long kbId);

    Optional<KbDocument> findByIdAndKbId(Long id, Long kbId);

    long countByKbId(Long kbId);

    void deleteByKbId(Long kbId);
}
