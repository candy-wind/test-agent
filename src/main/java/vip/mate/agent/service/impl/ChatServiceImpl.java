package vip.mate.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.agent.config.AgentProperties;
import vip.mate.agent.dto.ChatMessage;
import vip.mate.agent.dto.ChatRequest;
import vip.mate.agent.dto.ChatResponse;
import vip.mate.agent.service.ChatService;
import vip.mate.agent.service.SearchService;

import vip.mate.agent.service.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天服务实现（支持 Function Calling / Tool Use）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final AgentProperties agentProperties;
    private final SearchService searchService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public ChatResponse chat(ChatRequest request) {
        AgentProperties.ProviderConfig provider = resolveProvider(request);

        // 如果启用搜索，先搜索再把结果注入上下文
        List<ChatResponse.SearchResult> searchResults = null;
        if (request.isSearch() && agentProperties.getSearch().isEnabled()) {
            String query = extractLastUserMessage(request.getMessages());
            if (query != null) {
                searchResults = searchService.search(query, agentProperties.getSearch().getMaxResults());
                injectSearchResults(request, searchResults);
            }
        }

        String model = resolveModel(provider, request);
        boolean useTools = request.isTools();

        try {
            // Function Calling 循环：LLM 可能返回 tool_calls → 执行 → 回传 → 直到返回最终内容
            for (int round = 0; round < 5; round++) {
                if (request.getMessages() == null) {
                    request.setMessages(new ArrayList<>());
                }
                String body = buildRequestBody(provider, model, request, false, useTools);

                HttpResponse<String> resp = httpClient.send(
                        buildHttpRequest(provider, body),
                        HttpResponse.BodyHandlers.ofString()
                );

                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    log.error("模型调用失败: provider={} model={} HTTP={} body={}",
                            request.getProvider(), model, resp.statusCode(), resp.body());
                    return ChatResponse.builder()
                            .content("模型调用失败 (HTTP " + resp.statusCode() + "): " + resp.body())
                            .model(model)
                            .searchResults(searchResults)
                            .build();
                }

                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode messageNode = root.path("choices").path(0).path("message");
                JsonNode toolCallsNode = messageNode.path("tool_calls");

                if (useTools && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                    // LLM 请求调用工具：先添加 assistant 消息（含 tool_calls），再逐个执行工具并追加 tool 结果消息
                    ChatMessage assistantMsg = objectMapper.treeToValue(messageNode, ChatMessage.class);
                    request.getMessages().add(assistantMsg);

                    for (JsonNode tc : toolCallsNode) {
                        String fnName = tc.path("function").path("name").asText();
                        String fnArgs = tc.path("function").path("arguments").asText("{}");
                        String toolCallId = tc.path("id").asText();

                        log.info("[chat] 工具调用 round={} tool={} id={}", round, fnName, toolCallId);
                        String result = toolRegistry.executeTool(fnName, fnArgs);

                        request.getMessages().add(ChatMessage.builder()
                                .role("tool")
                                .content(result)
                                .toolCallId(toolCallId)
                                .build());
                    }
                    // 继续下一轮，让 LLM 基于工具结果生成最终回复
                    continue;
                }

                // 无工具调用：返回最终内容
                String content = messageNode.path("content").asText();
                return ChatResponse.builder()
                        .content(content)
                        .model(model)
                        .searchResults(searchResults)
                        .build();
            }

            // 超过最大轮数仍未完成
            return ChatResponse.builder()
                    .content("工具调用轮次过多，请简化问题后重试。")
                    .model(model)
                    .searchResults(searchResults)
                    .build();
        } catch (Exception e) {
            log.error("聊天请求失败", e);
            return ChatResponse.builder()
                    .content("请求失败: " + e.getMessage())
                    .model(model)
                    .searchResults(searchResults)
                    .build();
        }
    }

    @Override
    public SseEmitter chatStream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        executor.execute(() -> {
            try {
                AgentProperties.ProviderConfig provider = resolveProvider(request);
                String model = resolveModel(provider, request);

                // 搜索注入
                if (request.isSearch() && agentProperties.getSearch().isEnabled()) {
                    String query = extractLastUserMessage(request.getMessages());
                    if (query != null) {
                        var results = searchService.search(query, agentProperties.getSearch().getMaxResults());
                        injectSearchResults(request, results);
                        emitter.send(SseEmitter.event()
                                .name("search")
                                .data(objectMapper.writeValueAsString(results)));
                    }
                }

                boolean useTools = request.isTools();

                // Function Calling 循环
                for (int round = 0; round < 5; round++) {
                    if (request.getMessages() == null) {
                        request.setMessages(new ArrayList<>());
                    }
                    String body = buildRequestBody(provider, model, request, true, useTools);
                    HttpResponse<java.io.InputStream> resp = httpClient.send(
                            buildHttpRequest(provider, body),
                            HttpResponse.BodyHandlers.ofInputStream()
                    );

                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                        log.error("模型调用失败: provider={} model={} HTTP={} body={}",
                                request.getProvider(), model, resp.statusCode(), errBody);
                        throw new RuntimeException("模型调用失败 (HTTP " + resp.statusCode() + "): " + errBody);
                    }

                    // 解析流式响应，收集 tool_calls 或转发 delta
                    List<ChatMessage.ToolCall> collectedTools = new ArrayList<>();
                    String collectedContent = streamAndCollect(resp.body(), emitter, collectedTools);

                    if (useTools && !collectedTools.isEmpty()) {
                        // LLM 请求工具调用：发送 tool_call 事件，执行工具，发送 tool_result 事件
                        for (ChatMessage.ToolCall tc : collectedTools) {
                            emitter.send(SseEmitter.event()
                                    .name("tool_call")
                                    .data(objectMapper.writeValueAsString(tc.getFunction())));

                            String result = toolRegistry.executeTool(
                                    tc.getFunction().getName(),
                                    tc.getFunction().getArguments());

                            emitter.send(SseEmitter.event()
                                    .name("tool_result")
                                    .data(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                            .put("name", tc.getFunction().getName())
                                            .put("result", result))));

                            request.getMessages().add(ChatMessage.builder()
                                    .role("tool")
                                    .content(result)
                                    .toolCallId(tc.getId())
                                    .build());
                        }

                        // 添加 assistant 消息（含 tool_calls）
                        ChatMessage assistantMsg = ChatMessage.builder()
                                .role("assistant")
                                .content(collectedContent.isEmpty() ? null : collectedContent)
                                .toolCalls(collectedTools)
                                .build();
                        request.getMessages().add(assistantMsg);
                        // 继续下一轮
                        continue;
                    }

                    // 无工具调用：正常结束
                    emitter.send(SseEmitter.event().name("done").data(model));
                    emitter.complete();
                    return;
                }

                // 超过最大轮数
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("工具调用轮次过多"));
                emitter.complete();
            } catch (Exception e) {
                log.error("流式聊天失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("流式请求失败: " + e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 读取流式响应体，转发 delta 给前端，同时收集可能的 tool_calls
     */
    private String streamAndCollect(java.io.InputStream inputStream, SseEmitter emitter,
                                    List<ChatMessage.ToolCall> collectedTools) throws Exception {
        StringBuilder contentBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data.trim())) {
                        break;
                    }
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");

                    // 处理内容 delta
                    String deltaContent = delta.path("content").asText("");
                    if (!deltaContent.isEmpty()) {
                        contentBuilder.append(deltaContent);
                        emitter.send(SseEmitter.event()
                                .name("delta")
                                .data(deltaContent));
                    }

                    // 处理 tool_calls delta（流式分片到达，需要拼接）
                    JsonNode toolCallsDelta = delta.path("tool_calls");
                    if (toolCallsDelta.isArray()) {
                        for (JsonNode tcd : toolCallsDelta) {
                            int idx = tcd.path("index").asInt(0);
                            String id = tcd.has("id") ? tcd.path("id").asText() : null;
                            String name = tcd.path("function").has("name")
                                    ? tcd.path("function").path("name").asText() : null;
                            String args = tcd.path("function").has("arguments")
                                    ? tcd.path("function").path("arguments").asText() : "";

                            // 扩容
                            while (collectedTools.size() <= idx) {
                                collectedTools.add(ChatMessage.ToolCall.builder()
                                        .id("").type("function")
                                        .function(ChatMessage.Function.builder()
                                                .name("").arguments("").build())
                                        .build());
                            }
                            ChatMessage.ToolCall existing = collectedTools.get(idx);
                            if (id != null) existing.setId(id);
                            if (name != null) existing.getFunction().setName(name);
                            existing.getFunction().setArguments(
                                    existing.getFunction().getArguments() + args);
                        }
                    }
                }
            }
        }
        return contentBuilder.toString();
    }

    // ==================== 私有方法 ====================

    private AgentProperties.ProviderConfig resolveProvider(ChatRequest request) {
        String name = (request.getProvider() != null && !request.getProvider().isBlank())
                ? request.getProvider()
                : agentProperties.getDefaultProvider();
        AgentProperties.ProviderConfig config = agentProperties.getProviders().get(name);
        if (config == null) {
            throw new IllegalArgumentException("未找到模型提供商: " + name);
        }
        return config;
    }

    private String resolveModel(AgentProperties.ProviderConfig provider, ChatRequest request) {
        // 优先使用请求中指定的具体模型
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel().trim();
        }
        // 其次根据请求决定使用推理模型
        if (request.isReasoner() && provider.getReasonerModel() != null) {
            return provider.getReasonerModel();
        }
        // 如果消息中包含图片，使用视觉模型
        if (hasImages(request.getMessages()) && provider.getVisionModel() != null) {
            return provider.getVisionModel();
        }
        return provider.getChatModel();
    }

    private boolean hasImages(List<ChatMessage> messages) {
        return messages != null && messages.stream()
                .anyMatch(m -> m.getImages() != null && !m.getImages().isEmpty());
    }

    private String buildRequestBody(AgentProperties.ProviderConfig provider,
                                    String model, ChatRequest request, boolean stream,
                                    boolean includeTools) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("stream", stream);

            ArrayNode messagesArray = root.putArray("messages");
            for (ChatMessage msg : request.getMessages()) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.getRole());

                // tool 角色消息：工具调用结果
                if ("tool".equals(msg.getRole())) {
                    msgNode.put("content", msg.getContent() != null ? msg.getContent() : "");
                    if (msg.getToolCallId() != null) {
                        msgNode.put("tool_call_id", msg.getToolCallId());
                    }
                    continue;
                }

                // assistant 消息可能含 tool_calls
                if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        msgNode.put("content", msg.getContent());
                    } else {
                        msgNode.putNull("content");
                    }
                    ArrayNode tcArray = msgNode.putArray("tool_calls");
                    for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                        ObjectNode tcNode = tcArray.addObject();
                        tcNode.put("id", tc.getId());
                        tcNode.put("type", "function");
                        ObjectNode fnNode = tcNode.putObject("function");
                        fnNode.put("name", tc.getFunction().getName());
                        fnNode.put("arguments", tc.getFunction().getArguments());
                    }
                    continue;
                }

                // 如果有图片，用 content array 格式
                if (msg.getImages() != null && !msg.getImages().isEmpty()) {
                    ArrayNode contentArray = msgNode.putArray("content");
                    contentArray.addObject()
                            .put("type", "text")
                            .put("text", msg.getContent() != null ? msg.getContent() : "");
                    for (String img : msg.getImages()) {
                        contentArray.addObject()
                                .putObject("image_url")
                                .put("url", img);
                    }
                } else {
                    msgNode.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
            }

            // 加入 tools 定义
            if (includeTools) {
                JsonNode toolsNode = objectMapper.readTree(toolRegistry.buildToolsJson());
                root.set("tools", toolsNode.get("tools"));
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    private HttpRequest buildHttpRequest(AgentProperties.ProviderConfig provider, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(provider.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private String extractLastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return null;
    }

    private void injectSearchResults(ChatRequest request, List<ChatResponse.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("\n\n[搜索结果]\n");
        for (int i = 0; i < results.size(); i++) {
            ChatResponse.SearchResult r = results.get(i);
            sb.append(String.format("%d. %s\n   %s\n   %s\n",
                    i + 1, r.getTitle(), r.getUrl(), r.getSnippet()));
        }
        // 追加到最后一条 user 消息
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            if ("user".equals(request.getMessages().get(i).getRole())) {
                ChatMessage msg = request.getMessages().get(i);
                msg.setContent(msg.getContent() + sb);
                break;
            }
        }
    }
}