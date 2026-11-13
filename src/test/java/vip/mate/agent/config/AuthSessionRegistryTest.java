package vip.mate.agent.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionRegistryTest {

    @Test
    void issuesAndInvalidatesSessions() {
        AuthSessionRegistry registry = new AuthSessionRegistry();
        String token = registry.issue("admin", 1L, 2L, 24);

        assertThat(token).isNotBlank();
        assertThat(registry.verify(token).username()).isEqualTo("admin");
        assertThat(registry.verify(token).workspaceId()).isEqualTo(2L);
        assertThat(registry.verify(null)).isNull();
        assertThat(registry.verify("forged-token")).isNull();

        registry.invalidate(token);
        assertThat(registry.verify(token)).isNull();
    }

    @Test
    void workspaceContextPropagatesToAsyncThreads() throws Exception {
        WorkspaceContext.Identity identity = new WorkspaceContext.Identity("admin", 1L, 42L);
        WorkspaceContext.set(identity);
        AtomicReference<WorkspaceContext.Identity> seen = new AtomicReference<>();
        try {
            Thread thread = new Thread(WorkspaceContext.wrap(() -> seen.set(WorkspaceContext.get())));
            thread.start();
            thread.join();
        } finally {
            WorkspaceContext.clear();
        }
        assertThat(seen.get()).isEqualTo(identity);
        assertThat(WorkspaceContext.get()).isNull();
    }
}
