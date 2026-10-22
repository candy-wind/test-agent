package vip.mate.agent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.agent.dto.ChatRequest;
import vip.mate.agent.dto.ChatResponse;

/**
 * 聊天服务
 */
public interface ChatService {

    /**
     * 普通聊天（阻塞返回）
     */
    ChatResponse chat(ChatRequest request);

    /**
     * 流式聊天（SSE）
     */
    SseEmitter chatStream(ChatRequest request);
}
