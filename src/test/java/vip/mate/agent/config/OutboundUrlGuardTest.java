package vip.mate.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundUrlGuardTest {

    private final OutboundUrlGuard guard = new OutboundUrlGuard("", false);

    @Test
    void rejectsLoopbackAndPrivateAddresses() {
        assertThatThrownBy(() -> guard.requireAllowed("http://127.0.0.1:11434/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
        assertThatThrownBy(() -> guard.requireAllowed("http://192.168.1.10/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.requireAllowed("http://10.0.0.5/v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCloudMetadataAddress() {
        assertThatThrownBy(() -> guard.requireAllowed("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void rejectsNonHttpSchemesAndUserInfo() {
        assertThatThrownBy(() -> guard.requireAllowed("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> guard.requireAllowed("http://user:pass@8.8.8.8/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户信息");
    }

    @Test
    void allowsLiteralPublicAddress() {
        assertThatCode(() -> guard.requireAllowed("https://8.8.8.8/v1")).doesNotThrowAnyException();
        assertThat(guard.requireAllowed("https://8.8.8.8/v1").getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    void privateHostsCanBeExplicitlyEnabled() {
        OutboundUrlGuard local = new OutboundUrlGuard("", true);
        assertThatCode(() -> local.requireAllowed("http://127.0.0.1:11434/v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowlistRestrictsHosts() {
        OutboundUrlGuard allowlist = new OutboundUrlGuard("api.example.com, *.deepseek.com", false);
        assertThatCode(() -> allowlist.requireAllowed("https://api.example.com/v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> allowlist.requireAllowed("https://api.deepseek.com/v1"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> allowlist.requireAllowed("https://evil.example.org/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("允许清单");
    }
}
