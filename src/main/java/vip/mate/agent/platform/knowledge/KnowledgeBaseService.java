package vip.mate.agent.platform.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.agent.platform.knowledge.dto.ChunkView;
import vip.mate.agent.platform.knowledge.dto.DocumentView;
import vip.mate.agent.platform.knowledge.dto.KbRequest;
import vip.mate.agent.platform.knowledge.dto.KbView;
import vip.mate.agent.platform.knowledge.dto.SearchResult;
import vip.mate.agent.platform.model.ModelConfigService;
import vip.mate.agent.platform.model.dto.ModelEndpoint;
import vip.mate.agent.platform.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库主流程：知识库/文档管理、解析切分、向量化、检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KbKnowledgeBaseRepository kbRepository;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final TextExtractor textExtractor;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final WorkspaceService workspaceService;
    private final ModelConfigService modelConfigService;

    @Value("${platform.knowledge.storage-dir:./data/kb}")
    private String storageDir;

    @Value("${platform.knowledge.default-top-k:5}")
    private int defaultTopK;

    @Value("${platform.knowledge.max-top-k:20}")
    private int maxTopK;

    @Value("${platform.knowledge.max-context-chars:8000}")
    private int maxContextChars;

    @Value("${platform.knowledge.vectorization-enabled:false}")
    private boolean vectorizationEnabled;

    /** 英文/数字关键词，如 mysql、spring、java8（不含 LIKE 通配符 _ %） */
    private static final Pattern ASCII_TERM = Pattern.compile("\\p{Alnum}{2,}");
    /** 连续汉字短语 */
    private static final Pattern HAN_RUN = Pattern.compile("\\p{IsHan}{2,}");
    /** 疑问/口语化片段，不作为检索关键词 */
    private static final Set<String> QUESTION_WORDS = Set.of(
            "什么", "怎么", "如何", "哪些", "哪个", "是否", "可以", "能否", "主要",
            "介绍", "一下", "这个", "那个", "一个", "我们", "你们", "他们", "它们",
            "的是", "是什", "么是", "有什", "为什", "区别", "作用", "用法");
    private static final int MAX_KEYWORD_TERMS = 16;

    // ==================== 知识库 ====================

    @Transactional(readOnly = true)
    public List<KbView> list() {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        String embeddingRoute = embeddingRouteText();
        return kbRepository.findByWorkspaceIdOrderByIdDesc(workspaceId).stream()
                .map(kb -> toView(kb, embeddingRoute))
                .toList();
    }

    @Transactional
    public KbView create(KbRequest request) {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        int chunkSize = normalizeChunkSize(request.chunkSize());
        int overlap = normalizeOverlap(request.chunkOverlap(), chunkSize);
        KbKnowledgeBase kb = KbKnowledgeBase.builder()
                .workspaceId(workspaceId)
                .name(request.name().trim())
                .description(request.description())
                .chunkSize(chunkSize)
                .chunkOverlap(overlap)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build();
        return toView(kbRepository.save(kb), embeddingRouteText());
    }

    @Transactional
    public KbView update(Long id, KbRequest request) {
        KbKnowledgeBase kb = requireKb(id);
        int chunkSize = normalizeChunkSize(request.chunkSize());
        kb.setName(request.name().trim());
        kb.setDescription(request.description());
        kb.setChunkSize(chunkSize);
        kb.setChunkOverlap(normalizeOverlap(request.chunkOverlap(), chunkSize));
        return toView(kbRepository.save(kb), embeddingRouteText());
    }

    @Transactional
    public void delete(Long id) {
        KbKnowledgeBase kb = requireKb(id);
        vectorStoreCleanup(kb.getId());
        documentRepository.deleteByKbId(kb.getId());
        kbRepository.deleteById(kb.getId());
        deleteStoredDirectory(kb);
    }

    // ==================== 文档 ====================

    @Transactional(readOnly = true)
    public List<DocumentView> listDocuments(Long kbId) {
        requireKb(kbId);
        return documentRepository.findByKbIdOrderByIdDesc(kbId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 上传并同步完成解析、切分；启用向量化时再写入向量。失败时文档保留为 FAILED，并记录原因。
     */
    public DocumentView upload(Long kbId, MultipartFile file) {
        KbKnowledgeBase kb = requireKb(kbId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "未命名文档";
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage(), e);
        }

        String text = textExtractor.extract(originalName, file.getContentType(), bytes);
        String objectKey = storeFile(kb, originalName, bytes);

        KbDocument document = KbDocument.builder()
                .kbId(kb.getId())
                .workspaceId(kb.getWorkspaceId())
                .name(originalName)
                .sourceType("UPLOAD")
                .objectKey(objectKey)
                .mime(file.getContentType())
                .size((long) bytes.length)
                .checksum(sha256(bytes))
                .status(KbDocument.STATUS_PENDING)
                .createdAt(Instant.now())
                .build();
        document = documentRepository.save(document);

        try {
            List<String> pieces = textChunker.chunk(text, kb.getChunkSize(), kb.getChunkOverlap());
            if (pieces.isEmpty()) {
                throw new IllegalArgumentException("切分后没有有效文本");
            }
            List<KbChunk> chunks = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                chunks.add(KbChunk.builder()
                        .kbId(kb.getId())
                        .documentId(document.getId())
                        .seq(i)
                        .content(pieces.get(i))
                        .tokenCount(textChunker.estimateTokens(pieces.get(i)))
                        .build());
            }
            chunks = chunkRepository.saveAll(chunks);

            if (vectorizationEnabled) {
                long start = System.currentTimeMillis();
                List<float[]> vectors = embeddingClient.embed(
                        chunks.stream().map(KbChunk::getContent).toList());
                for (int i = 0; i < chunks.size(); i++) {
                    vectorStore.upsert(chunks.get(i).getId(), vectors.get(i));
                }
                log.info("[kb] 文档向量化完成: kb={} doc={} chunks={} cost={}ms",
                        kb.getId(), document.getId(), chunks.size(), System.currentTimeMillis() - start);
            } else {
                log.info("[kb] 已跳过向量化（关键词检索模式）: kb={} doc={} chunks={}",
                        kb.getId(), document.getId(), chunks.size());
            }
            document.setStatus(KbDocument.STATUS_READY);
            document.setErrorMsg(null);
        } catch (Exception e) {
            document.setStatus(KbDocument.STATUS_FAILED);
            document.setErrorMsg(truncate(messageOf(e), 1000));
            log.warn("[kb] 文档处理失败: kb={} doc={} name={}", kb.getId(), document.getId(), originalName, e);
        }
        document = documentRepository.save(document);
        return toView(document);
    }

    @Transactional
    public void deleteDocument(Long kbId, Long documentId) {
        requireKb(kbId);
        KbDocument document = documentRepository.findByIdAndKbId(documentId, kbId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在: " + documentId));
        vectorStore.deleteByDocument(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        deleteStoredFile(document);
    }

    @Transactional(readOnly = true)
    public List<ChunkView> listChunks(Long kbId, Long documentId) {
        requireKb(kbId);
        documentRepository.findByIdAndKbId(documentId, kbId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在: " + documentId));
        return chunkRepository.findByDocumentIdOrderBySeqAsc(documentId).stream()
                .map(c -> new ChunkView(c.getId(), c.getSeq(), c.getContent(), c.getTokenCount()))
                .toList();
    }

    /**
     * 重新向量化（换 embedding 模型后可用），复用已有分块。
     */
    public DocumentView reindex(Long kbId, Long documentId) {
        requireKb(kbId);
        KbDocument document = documentRepository.findByIdAndKbId(documentId, kbId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在: " + documentId));
        List<KbChunk> chunks = chunkRepository.findByDocumentIdOrderBySeqAsc(documentId);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("该文档没有可重建的分块，请删除后重新上传");
        }
        if (!vectorizationEnabled) {
            document.setStatus(KbDocument.STATUS_READY);
            document.setErrorMsg(null);
            log.info("[kb] 向量化已关闭，重建仅校验分块: doc={} chunks={}", documentId, chunks.size());
            return toView(documentRepository.save(document));
        }
        try {
            vectorStore.deleteByDocument(documentId);
            List<float[]> vectors = embeddingClient.embed(
                    chunks.stream().map(KbChunk::getContent).toList());
            for (int i = 0; i < chunks.size(); i++) {
                vectorStore.upsert(chunks.get(i).getId(), vectors.get(i));
            }
            document.setStatus(KbDocument.STATUS_READY);
            document.setErrorMsg(null);
        } catch (Exception e) {
            document.setStatus(KbDocument.STATUS_FAILED);
            document.setErrorMsg(truncate(messageOf(e), 1000));
        }
        return toView(documentRepository.save(document));
    }

    // ==================== 检索 ====================

    public List<SearchResult> search(Long kbId, String query, Integer topK) {
        requireKb(kbId);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        int limit = topK == null ? defaultTopK : Math.max(1, Math.min(topK, maxTopK));
        if (!vectorizationEnabled) {
            return keywordSearch(kbId, query.trim(), limit);
        }
        float[] queryVector = embeddingClient.embedOne(query.trim());
        return vectorStore.search(kbId, queryVector, limit).stream()
                .map(c -> new SearchResult(c.chunkId(), c.documentId(), c.documentName(),
                        c.seq(), c.content(), round(c.score())))
                .toList();
    }

    private List<SearchResult> keywordSearch(Long kbId, String query, int limit) {
        String terms = String.join("|", extractKeywordTerms(query));
        return chunkRepository.keywordSearch(kbId, terms, limit).stream()
                .map(row -> new SearchResult(
                        ((Number) row[0]).longValue(),
                        ((Number) row[1]).longValue(),
                        (String) row[2],
                        ((Number) row[3]).intValue(),
                        (String) row[4],
                        0d))
                .toList();
    }

    /**
     * 从自然语言问题里抽取检索关键词：英文/数字整词 + 汉字双字词。
     * 例如“mysql主要什么” → [mysql, 要什]，避免整句 LIKE 几乎匹配不到内容。
     */
    private List<String> extractKeywordTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher ascii = ASCII_TERM.matcher(query);
        while (ascii.find() && terms.size() < MAX_KEYWORD_TERMS) {
            terms.add(ascii.group());
        }
        Matcher han = HAN_RUN.matcher(query);
        while (han.find() && terms.size() < MAX_KEYWORD_TERMS) {
            String run = han.group();
            for (int i = 0; i + 2 <= run.length() && terms.size() < MAX_KEYWORD_TERMS; i++) {
                String bigram = run.substring(i, i + 2);
                if (!QUESTION_WORDS.contains(bigram)) {
                    terms.add(bigram);
                }
            }
        }
        if (terms.isEmpty()) {
            terms.add(query);
        }
        return new ArrayList<>(terms);
    }

    /**
     * 供 RAG 问答使用：返回检索结果和拼装好的上下文字符串。
     */
    public RagContext retrieve(Long kbId, String query, Integer topK) {
        List<SearchResult> results = search(kbId, query, topK);
        String context = buildContext(results);
        return new RagContext(results, context);
    }

    private String buildContext(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String match = vectorizationEnabled ? "相似度 " + r.score() : "关键词命中";
            String block = "\n[资料" + (i + 1) + "] 文档：" + r.documentName()
                    + "（片段 #" + r.seq() + "，" + match + "）\n"
                    + r.content() + "\n";
            if (sb.length() + block.length() > maxContextChars) {
                break;
            }
            sb.append(block);
        }
        return sb.toString();
    }

    public record RagContext(List<SearchResult> results, String context) {
    }

    // ==================== 内部方法 ====================

    private KbKnowledgeBase requireKb(Long id) {
        Long workspaceId = workspaceService.defaultWorkspaceId();
        return kbRepository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NoSuchElementException("知识库不存在: " + id));
    }

    private int normalizeChunkSize(Integer size) {
        if (size == null) {
            return 600;
        }
        return Math.max(100, Math.min(size, 4000));
    }

    private int normalizeOverlap(Integer overlap, int chunkSize) {
        if (overlap == null) {
            return Math.min(80, chunkSize / 2);
        }
        return Math.max(0, Math.min(overlap, chunkSize / 2));
    }

    private String embeddingRouteText() {
        if (!vectorizationEnabled) {
            return null;
        }
        try {
            ModelEndpoint endpoint = modelConfigService.resolveEndpoint("EMBEDDING");
            return endpoint == null ? null : endpoint.providerName() + " / " + endpoint.modelKey();
        } catch (Exception e) {
            return null;
        }
    }

    private KbView toView(KbKnowledgeBase kb, String embeddingRoute) {
        return new KbView(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getChunkSize(),
                kb.getChunkOverlap(),
                documentRepository.countByKbId(kb.getId()),
                chunkRepository.countByKbId(kb.getId()),
                vectorizationEnabled,
                embeddingRoute,
                kb.getCreatedAt());
    }

    private DocumentView toView(KbDocument document) {
        return new DocumentView(
                document.getId(),
                document.getKbId(),
                document.getName(),
                document.getMime(),
                document.getSize(),
                document.getStatus(),
                document.getErrorMsg(),
                chunkRepository.countByDocumentId(document.getId()),
                document.getCreatedAt());
    }

    private String storeFile(KbKnowledgeBase kb, String originalName, byte[] bytes) {
        try {
            Path root = Paths.get(storageDir).toAbsolutePath().normalize();
            Path dir = root.resolve(String.valueOf(kb.getWorkspaceId())).resolve(String.valueOf(kb.getId()));
            Files.createDirectories(dir);
            String extension = extensionOf(originalName);
            String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
            Files.copy(new java.io.ByteArrayInputStream(bytes), dir.resolve(storedName),
                    StandardCopyOption.REPLACE_EXISTING);
            return kb.getWorkspaceId() + "/" + kb.getId() + "/" + storedName;
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    private void deleteStoredFile(KbDocument document) {
        if (document.getObjectKey() == null) {
            return;
        }
        try {
            Path root = Paths.get(storageDir).toAbsolutePath().normalize();
            Files.deleteIfExists(root.resolve(document.getObjectKey()).normalize());
        } catch (Exception e) {
            log.warn("[kb] 删除原文件失败: {}", document.getObjectKey(), e);
        }
    }

    private void deleteStoredDirectory(KbKnowledgeBase kb) {
        try {
            Path root = Paths.get(storageDir).toAbsolutePath().normalize();
            Path dir = root.resolve(String.valueOf(kb.getWorkspaceId())).resolve(String.valueOf(kb.getId()));
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("[kb] 删除知识库文件目录失败: {}", kb.getId(), e);
        }
    }

    private void vectorStoreCleanup(Long kbId) {
        try {
            var documentIds = documentRepository.findByKbIdOrderByIdDesc(kbId).stream()
                    .map(KbDocument::getId).toList();
            documentIds.forEach(vectorStore::deleteByDocument);
            chunkRepository.deleteByKbId(kbId);
        } catch (Exception e) {
            log.warn("[kb] 清理知识库向量失败: {}", kbId, e);
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
