package vip.mate.agent.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.agent.dto.ChatRequest;
import vip.mate.agent.dto.ChatResponse;
import vip.mate.agent.service.ChatService;

/**
 * 聊天接口
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 普通聊天（阻塞返回）
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        log.info("[chat] provider={} reasoner={} search={} msgs={}",
                request.getProvider(), request.isReasoner(), request.isSearch(), request.getMessages().size());
        ChatResponse resp = chatService.chat(request);
        log.info("[chat] done model={} content_len={}",
                resp.getModel(), resp.getContent() == null ? 0 : resp.getContent().length());
        return resp;
    }

    /**
     * 流式聊天（SSE）
     * 事件类型：
     *   - search: 搜索结果
     *   - delta:  增量文本
     *   - done:   结束（data 为模型名）
     *   - error:  错误
     */
    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("[chat/stream] provider={} reasoner={} search={} msgs={}",
                request.getProvider(), request.isReasoner(), request.isSearch(), request.getMessages().size());
        request.setStream(true);
        return chatService.chatStream(request);
    }
}