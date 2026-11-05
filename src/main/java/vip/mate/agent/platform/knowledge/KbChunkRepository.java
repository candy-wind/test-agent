package vip.mate.agent.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByDocumentIdOrderBySeqAsc(Long documentId);

    long countByDocumentId(Long documentId);

    long countByKbId(Long kbId);

    @Transactional
    void deleteByDocumentId(Long documentId);

    @Transactional
    void deleteByKbId(Long kbId);
}
