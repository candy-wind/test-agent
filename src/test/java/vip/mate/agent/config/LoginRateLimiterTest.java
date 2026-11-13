package vip.mate.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    @Test
    void locksAfterConsecutiveFailures() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("admin", 5, 15);
        }
        assertThat(limiter.isLocked("admin")).isTrue();
        assertThat(limiter.lockedSeconds("admin")).isGreaterThan(0);
    }

    @Test
    void successfulLoginResetsCounter() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.recordFailure("admin", 5, 15);
        limiter.recordFailure("admin", 5, 15);
        limiter.reset("admin");
        assertThat(limiter.isLocked("admin")).isFalse();
        limiter.recordFailure("admin", 5, 15);
        assertThat(limiter.isLocked("admin")).isFalse();
    }

    @Test
    void failuresAreIsolatedPerAccount() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("admin", 5, 15);
        }
        assertThat(limiter.isLocked("admin")).isTrue();
        assertThat(limiter.isLocked("other")).isFalse();
    }
}
