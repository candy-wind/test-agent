package vip.mate.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 出站地址防护（SSRF）。模型供应商 baseUrl 完全由页面配置，若不校验，
 * 登录用户可以把它指向云元数据地址或内网服务，借应用发起请求并回显结果。
 *
 * 默认策略：只允许 http/https，拒绝解析到 loopback / 私网 / 链路本地 / 保留地址；
 * 可通过 platform.outbound.allow-private-hosts=true 放行本地 Ollama/vLLM，
 * 或配置 platform.outbound.allowed-hosts 做严格的域名白名单（支持 *.example.com）。
 */
@Slf4j
@Component
public class OutboundUrlGuard {

    private final List<String> allowedHosts;
    private final boolean allowPrivateHosts;

    public OutboundUrlGuard(
            @Value("${platform.outbound.allowed-hosts:}") String allowedHosts,
            @Value("${platform.outbound.allow-private-hosts:false}") boolean allowPrivateHosts) {
        String raw = allowedHosts == null ? "" : allowedHosts;
        this.allowedHosts = Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .map(value -> value.replaceFirst("^https?://", "").replaceAll("/.*$", ""))
                .toList();
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /** 校验并返回可安全使用的 URI；不合规时抛 IllegalArgumentException。 */
    public URI requireAllowed(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("模型服务地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("模型服务地址格式不正确: " + rawUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("模型服务地址只支持 http/https: " + rawUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("模型服务地址缺少主机: " + rawUrl);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("模型服务地址不允许携带用户信息: " + rawUrl);
        }

        if (!allowedHosts.isEmpty()) {
            if (!matchesAllowlist(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("模型服务地址不在允许清单中: " + host
                        + "，如需放行请配置 platform.outbound.allowed-hosts");
            }
            return uri;
        }
        if (allowPrivateHosts) {
            return uri;
        }

        String lookupHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(lookupHost);
        } catch (UnknownHostException e) {
            // 解析不了的地址应用本身也连不上，记录后放行，避免离线环境下误伤正常配置
            log.debug("[outbound] 主机名解析失败，跳过内网检查: {}", host);
            return uri;
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                throw new IllegalArgumentException("模型服务地址指向内网/保留地址，已拒绝: " + host
                        + "（本地模型请显式设置 platform.outbound.allow-private-hosts=true）");
            }
        }
        return uri;
    }

    public void assertAllowed(String rawUrl) {
        requireAllowed(rawUrl);
    }

    private boolean matchesAllowlist(String host) {
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.")) {
                String suffix = allowed.substring(1);
                if (host.equals(allowed.substring(2)) || host.endsWith(suffix)) {
                    return true;
                }
            } else if (host.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isForbidden(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }
}
