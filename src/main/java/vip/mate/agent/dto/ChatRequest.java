package vip.mate.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 聊天请求
 */
@Data
public class ChatRequest {

    /**
     * 消息列表
     */
    @NotEmpty
    private List<ChatMessage> messages;

    /**
     * 模型提供商：deepseek / qwen，留空使用默认
     */
    private String provider;

    /**
     * 是否使用推理模型
     */
    private boolean reasoner;

    /**
     * 是否启用搜索
     */
    private boolean search;

    /**
     * 指定模型名称，留空则按 reasoner/vision 自动选择
     */
    private String model;

    /**
     * 是否流式返回
     */
    private boolean stream;

    /**
     * 是否启用工具调用（Function Calling），启用后 LLM 可自动调用票房查询等工具
     */
    private boolean tools;
}