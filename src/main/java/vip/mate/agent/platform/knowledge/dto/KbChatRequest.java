package vip.mate.agent.platform.knowledge.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import vip.mate.agent.dto.ChatMessage;

import java.util.List;

/**
 * 知识库 RAG 问答请求。messages 需包含完整的对话历史，最后一条为本次提问。
 */
@Data
public class KbChatRequest {

    @NotEmpty(message = "消息不能为空")
    private List<ChatMessage> messages;

    /** 检索条数，留空使用默认值 */
    private Integer topK;

    /** 可选：指定模型，留空使用 CHAT 路由 */
    private String model;
}
