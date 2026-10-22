package vip.mate.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    private String role;
    private String content;

    /**
     * 图片 URL 列表（用于视觉模型）
     */
    private List<String> images;

    /**
     * 助手消息中 LLM 返回的工具调用
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * 工具调用结果消息的关联 ID
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * 工具调用
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        /** 调用索引（流式拼接用） */
        private Integer index;
        private String id;
        private String type;
        private Function function;
    }

    /**
     * 工具函数定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {
        private String name;
        private String arguments;
    }
}
