package vip.mate.agent.platform.knowledge;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.agent.platform.common.ApiResponse;
import vip.mate.agent.platform.knowledge.dto.ChunkView;
import vip.mate.agent.platform.knowledge.dto.DocumentView;
import vip.mate.agent.platform.knowledge.dto.KbChatRequest;
import vip.mate.agent.platform.knowledge.dto.KbRequest;
import vip.mate.agent.platform.knowledge.dto.KbView;
import vip.mate.agent.platform.knowledge.dto.SearchRequest;
import vip.mate.agent.platform.knowledge.dto.SearchResult;

import java.util.List;

/**
 * 知识库接口（新增，不影响原有 /api/* 接口）。
 * 现有 AuthFilter 会对 /api/* 统一做登录校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbChatService kbChatService;

    // ==================== 知识库 ====================

    @GetMapping
    public ApiResponse<List<KbView>> list() {
        return ApiResponse.ok(knowledgeBaseService.list());
    }

    @PostMapping
    public ApiResponse<KbView> create(@Valid @RequestBody KbRequest request) {
        log.info("[kb] 新建知识库 name={}", request.name());
        return ApiResponse.ok(knowledgeBaseService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<KbView> update(@PathVariable Long id, @Valid @RequestBody KbRequest request) {
        return ApiResponse.ok(knowledgeBaseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return ApiResponse.ok(null);
    }

    // ==================== 文档 ====================

    @GetMapping("/{id}/documents")
    public ApiResponse<List<DocumentView>> listDocuments(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeBaseService.listDocuments(id));
    }

    @PostMapping("/{id}/documents")
    public ApiResponse<DocumentView> upload(@PathVariable Long id,
                                            @RequestParam("file") MultipartFile file) {
        log.info("[kb] 上传文档 kb={} name={} size={}", id, file.getOriginalFilename(), file.getSize());
        return ApiResponse.ok(knowledgeBaseService.upload(id, file));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id, @PathVariable Long documentId) {
        knowledgeBaseService.deleteDocument(id, documentId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/documents/{documentId}/chunks")
    public ApiResponse<List<ChunkView>> listChunks(@PathVariable Long id, @PathVariable Long documentId) {
        return ApiResponse.ok(knowledgeBaseService.listChunks(id, documentId));
    }

    @PostMapping("/{id}/documents/{documentId}/reindex")
    public ApiResponse<DocumentView> reindex(@PathVariable Long id, @PathVariable Long documentId) {
        return ApiResponse.ok(knowledgeBaseService.reindex(id, documentId));
    }

    // ==================== 检索与问答 ====================

    @PostMapping("/{id}/search")
    public ApiResponse<List<SearchResult>> search(@PathVariable Long id,
                                                  @Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(knowledgeBaseService.search(id, request.query(), request.topK()));
    }

    @PostMapping(value = "/{id}/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@PathVariable Long id, @Valid @RequestBody KbChatRequest request) {
        return kbChatService.chat(id, request);
    }
}
