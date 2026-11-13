package vip.mate.agent.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限速：同一「来源 IP + 用户名」连续失败达到阈值后锁定一段时间。
 * 纯内存实现，防止弱口令被暴力破解；重启后计数清零。
 */
@Component
public class LoginRateLimiter {

    private record Attempt(int failures, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String key) {
        Attempt attempt = attempts.get(key);
        return attempt != null && attempt.lockedUntil() != null
                && Instant.now().isBefore(attempt.lockedUntil());
    }

    public long lockedSeconds(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil() == null) {
            return 0;
        }
        long seconds = Instant.now().until(attempt.lockedUntil(), java.time.temporal.ChronoUnit.SECONDS);
        return Math.max(1, seconds);
    }

    public void recordFailure(String key, int maxAttempts, int lockoutMinutes) {
        attempts.compute(key, (k, previous) -> {
            int failures = previous == null ? 1 : previous.failures() + 1;
            Instant lockedUntil = previous == null ? null : previous.lockedUntil();
            if (failures >= Math.max(1, maxAttempts)) {
                lockedUntil = Instant.now().plusSeconds(Math.max(1, lockoutMinutes) * 60L);
                failures = 0;
            }
            return new Attempt(failures, lockedUntil);
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    @Scheduled(fixedDelay = 600_000L)
    public void purgeExpired() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry -> {
            Attempt attempt = entry.getValue();
            return attempt.lockedUntil() == null || now.isAfter(attempt.lockedUntil().plusSeconds(3600));
        });
    }
}
