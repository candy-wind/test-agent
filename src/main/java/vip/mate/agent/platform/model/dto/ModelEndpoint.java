package vip.mate.agent.platform.model.dto;

/**
 * 已解析的模型端点：路由 -> 模型 -> 供应商，API Key 已解密。
 * 仅用于服务端内部调用，绝不应直接返回给前端。
 */
public record ModelEndpoint(
        Long providerId,
        String providerName,
        String baseUrl,
        String apiKey,
        String modelKey
) {
}
