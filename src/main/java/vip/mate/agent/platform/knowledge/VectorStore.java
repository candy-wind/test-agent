package vip.mate.agent.platform.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pgvector 读写。JPA 不支持 vector 类型，这里直接用 JdbcTemplate。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStore {

    private static final String SEARCH_SQL = """
            SELECT c.id           AS chunk_id,
                   c.document_id  AS document_id,
                   c.seq          AS seq,
                   c.content      AS content,
                   d.name         AS document_name,
                   1 - (e.embedding <=> CAST(? AS vector)) AS score
            FROM kb_embedding e
            JOIN kb_chunk c ON c.id = e.chunk_id
            JOIN kb_document d ON d.id = c.document_id
            WHERE c.kb_id = ?
              AND d.status = 'READY'
            ORDER BY e.embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public void upsert(long chunkId, float[] vector) {
        jdbcTemplate.update(
                "INSERT INTO kb_embedding (chunk_id, embedding) VALUES (?, CAST(? AS vector)) "
                        + "ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding",
                chunkId, toLiteral(vector));
    }

    public List<ScoredChunk> search(long kbId, float[] queryVector, int topK) {
        String literal = toLiteral(queryVector);
        return jdbcTemplate.query(SEARCH_SQL,
                (rs, rowNum) -> new ScoredChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_name"),
                        rs.getInt("seq"),
                        rs.getString("content"),
                        rs.getDouble("score")),
                literal, kbId, literal, topK);
    }

    public void deleteByDocument(long documentId) {
        jdbcTemplate.update(
                "DELETE FROM kb_embedding WHERE chunk_id IN (SELECT id FROM kb_chunk WHERE document_id = ?)",
                documentId);
    }

    public long countByKb(long kbId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kb_embedding e JOIN kb_chunk c ON c.id = e.chunk_id WHERE c.kb_id = ?",
                Long.class, kbId);
        return count == null ? 0 : count;
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    public record ScoredChunk(
            long chunkId,
            long documentId,
            String documentName,
            int seq,
            String content,
            double score
    ) {
    }
}
