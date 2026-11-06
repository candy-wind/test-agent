package vip.mate.agent.platform.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByDocumentIdOrderBySeqAsc(Long documentId);

    /**
     * 关键词检索（不依赖向量）：terms 为用 '|' 连接的关键词，
     * 命中关键词越多、命中的词越长，排序越靠前。
     */
    @Query(value = """
            SELECT c.id, c.document_id, d.name, c.seq, c.content
            FROM kb_chunk c
            JOIN kb_document d ON d.id = c.document_id
            WHERE c.kb_id = :kbId
              AND d.status = 'READY'
              AND EXISTS (
                  SELECT 1 FROM unnest(string_to_array(:terms, '|')) t
                  WHERE c.content ILIKE CONCAT('%', t, '%')
              )
            ORDER BY (
                  SELECT count(*) FROM unnest(string_to_array(:terms, '|')) t
                  WHERE c.content ILIKE CONCAT('%', t, '%')
              ) DESC,
              (
                  SELECT COALESCE(sum(length(t)), 0) FROM unnest(string_to_array(:terms, '|')) t
                  WHERE c.content ILIKE CONCAT('%', t, '%')
              ) DESC,
              c.document_id, c.seq
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> keywordSearch(@Param("kbId") Long kbId,
                                 @Param("terms") String terms,
                                 @Param("limit") int limit);

    long countByDocumentId(Long documentId);

    long countByKbId(Long kbId);

    @Transactional
    void deleteByDocumentId(Long documentId);

    @Transactional
    void deleteByKbId(Long kbId);
}
