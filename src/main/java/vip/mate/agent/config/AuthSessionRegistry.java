package vip.mate.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端会话表。Token 只存在内存里，重启即失效、不落盘、不进日志。
 * 与旧实现的区别：绑定了真实的 userId / workspaceId，并定期清理过期会话。
 */
@Slf4j
@Component
public class AuthSessionRegistry {

    public record Session(String username, Long userId, Long workspaceId, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public Session verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (Instant.now().isAfter(session.expiresAt())) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    public String issue(String username, Long userId, Long workspaceId, long ttlHours) {
        long ttl = Math.max(1, ttlHours);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username, userId, workspaceId,
                Instant.now().plusSeconds(ttl * 3600)));
        return token;
    }

    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 每 5 分钟清理过期会话，避免内存无限增长 */
    @Scheduled(fixedDelay = 300_000L)
    public void purgeExpired() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    public int size() {
        return sessions.size();
    }
}
