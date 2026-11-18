package vip.mate.agent.platform.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import vip.mate.agent.config.AuthProperties;
import vip.mate.agent.platform.model.AiProviderRepository;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspace;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformBootstrapTest {

    @Mock private SysUserRepository userRepository;
    @Mock private SysWorkspaceRepository workspaceRepository;
    @Mock private SysWorkspaceMemberRepository memberRepository;
    @Mock private AiProviderRepository providerRepository;
    @Mock private AuthProperties authProperties;
    @InjectMocks private PlatformBootstrap bootstrap;

    @Test
    void keepsExistingPasswordWhenNoEnvPasswordConfigured() {
        when(authProperties.getUsername()).thenReturn("admin");
        when(authProperties.getPassword()).thenReturn("");
        when(authProperties.getPasswordHash()).thenReturn("");
        when(authProperties.getInitialUsers()).thenReturn("");

        SysUser existing = SysUser.builder()
                .id(1L).username("admin").passwordHash("PASSWORD-SET-IN-UI")
                .status("ACTIVE").role("USER").build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(SysUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                SysWorkspace.builder().id(7L).name("默认空间").build()));
        when(memberRepository.existsByWorkspaceIdAndUserId(7L, 1L)).thenReturn(true);
        when(providerRepository.count()).thenReturn(0L);

        bootstrap.run(mock(ApplicationArguments.class));

        assertThat(existing.getPasswordHash()).isEqualTo("PASSWORD-SET-IN-UI");
        assertThat(existing.getRole()).isEqualTo("ADMIN");
        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void syncsPasswordWhenExplicitlyConfigured() {
        when(authProperties.getUsername()).thenReturn("admin");
        when(authProperties.getPassword()).thenReturn("FixedPassw0rd");
        when(authProperties.getPasswordHash()).thenReturn("");
        when(authProperties.getInitialUsers()).thenReturn("");

        SysUser existing = SysUser.builder()
                .id(1L).username("admin").passwordHash("OLD")
                .status("ACTIVE").role("ADMIN").build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(SysUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                SysWorkspace.builder().id(7L).name("默认空间").build()));
        when(memberRepository.existsByWorkspaceIdAndUserId(7L, 1L)).thenReturn(true);
        when(providerRepository.count()).thenReturn(0L);

        bootstrap.run(mock(ApplicationArguments.class));

        assertThat(existing.getPasswordHash()).isNotEqualTo("OLD").startsWith("$2");
    }

    @Test
    void createsAdminWithDefaultPasswordOnFreshDatabase() {
        when(authProperties.getUsername()).thenReturn("admin");
        when(authProperties.getPassword()).thenReturn("");
        when(authProperties.getPasswordHash()).thenReturn("");
        when(authProperties.getInitialUsers()).thenReturn("");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.save(captor.capture())).thenAnswer(invocation -> {
            SysUser saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(workspaceRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                SysWorkspace.builder().id(7L).name("默认空间").build()));
        when(memberRepository.existsByWorkspaceIdAndUserId(7L, 1L)).thenReturn(true);
        when(providerRepository.count()).thenReturn(0L);

        bootstrap.run(mock(ApplicationArguments.class));

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertThat(encoder.matches("123456", captor.getValue().getPasswordHash())).isTrue();
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
    }
}
