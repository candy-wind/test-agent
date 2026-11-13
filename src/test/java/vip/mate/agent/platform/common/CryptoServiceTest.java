package vip.mate.agent.platform.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    @Test
    void failsFastWithoutSecretKey() {
        assertThatThrownBy(() -> new CryptoService("", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SECRET_KEY");
        assertThatThrownBy(() -> new CryptoService("dev-only-change-me", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devModeStillRequiresOptIn() {
        CryptoService crypto = new CryptoService("", true);
        assertThat(crypto.decrypt(crypto.encrypt("sk-test-1234"))).isEqualTo("sk-test-1234");
    }

    @Test
    void rejectsShortSecretAndMasksKeys() {
        assertThatThrownBy(() -> new CryptoService("short", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
        CryptoService crypto = new CryptoService("test-only-secret-key-for-unit-tests", false);
        assertThat(crypto.mask("sk-1234567890abcd")).isEqualTo("sk-****abcd");
    }
}
