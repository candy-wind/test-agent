package vip.mate.agent.platform.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.agent.platform.model.ModelConfigService;
import vip.mate.agent.platform.model.dto.ModelEndpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调用 OpenAI 兼容的 /embeddings 接口。
 * 模型与供应商来自工作空间的 EMBEDDING 路由，Key 在数据库中是加密存储的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${platform.knowledge.embedding-dimension:1024}")
    private int dimension;

    @Value("${platform.knowledge.embedding-batch-size:16}")
    private int batchSize;

    public float[] embedOne(String text) {
        List<float[]> vectors = embed(List.of(text));
        return vectors.get(0);
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ModelEndpoint endpoint = modelConfigService.resolveEndpoint("EMBEDDING");
        if (endpoint == null) {
            throw new IllegalStateException(
                    "未配置 EMBEDDING 路由。请先在「模型配置」中添加支持向量化的模型，并把 EMBEDDING 路由指向它");
        }

        int size = Math.max(1, batchSize);
        List<float[]> result = new ArrayList<>(texts.size());
        for (int offset = 0; offset < texts.size(); offset += size) {
            List<String> batch = texts.subList(offset, Math.min(texts.size(), offset + size));
            result.addAll(requestBatch(endpoint, batch));
        }
        return result;
    }

    private List<float[]> requestBatch(ModelEndpoint endpoint, List<String> batch) {
        String url = endpoint.baseUrl() + "/embeddings";
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", endpoint.modelKey());
            ArrayNode input = root.putArray("input");
            batch.forEach(input::add);
            String body = objectMapper.writeValueAsString(root);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + endpoint.apiKey());
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("向量化服务返回 HTTP " + response.statusCode()
                        + "：" + truncate(response.body(), 400));
            }
            return parseVectors(response.body(), batch.size(), endpoint);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[kb] 调用 embedding 失败: url={} model={}", url, endpoint.modelKey(), e);
            throw new IllegalStateException("调用向量化服务失败：" + e.getMessage(), e);
        }
    }

    private List<float[]> parseVectors(String body, int expected, ModelEndpoint endpoint) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("向量化服务返回格式不正确，缺少 data 数组：" + truncate(body, 400));
        }

        List<float[]> vectors = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            vectors.add(null);
        }
        int fallbackIndex = 0;
        for (JsonNode item : data) {
            int index = item.has("index") ? item.path("index").asInt(fallbackIndex) : fallbackIndex;
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray()) {
                throw new IllegalStateException("向量化服务返回的 embedding 不是数组");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            if (vector.length != dimension) {
                throw new IllegalStateException("向量维度不一致：服务商 " + endpoint.providerName()
                        + " 的模型 " + endpoint.modelKey() + " 返回 " + vector.length
                        + " 维，而数据库 kb_embedding 是 vector(" + dimension + ")。"
                        + "请换用 " + dimension + " 维的 embedding 模型（如通义 text-embedding-v3）");
            }
            if (index >= 0 && index < expected) {
                vectors.set(index, vector);
            } else {
                vectors.set(Math.min(fallbackIndex, expected - 1), vector);
            }
            fallbackIndex++;
        }
        for (int i = 0; i < expected; i++) {
            if (vectors.get(i) == null) {
                throw new IllegalStateException("向量化服务返回数量不足，期望 " + expected + " 条");
            }
        }
        return vectors;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
