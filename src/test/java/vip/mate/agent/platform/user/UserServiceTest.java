package vip.mate.agent.platform.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import vip.mate.agent.config.WorkspaceContext;
import vip.mate.agent.platform.common.AuditLogService;
import vip.mate.agent.platform.common.ForbiddenException;
import vip.mate.agent.platform.user.dto.CreateUserRequest;
import vip.mate.agent.platform.user.dto.ResetPasswordRequest;
import vip.mate.agent.platform.user.dto.UpdateUserRequest;
import vip.mate.agent.platform.user.dto.UserView;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceMember;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.WorkspaceService;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock private SysUserRepository userRepository;
    @Mock private SysWorkspaceMemberRepository memberRepository;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private UserService service;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(new WorkspaceContext.Identity("admin", 1L, 7L, "ADMIN"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin()));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void rejectsNonAdmin() {
        WorkspaceContext.set(new WorkspaceContext.Identity("bob", 2L, 7L, "USER"));
        when(userRepository.findById(2L)).thenReturn(Optional.of(SysUser.builder()
                .id(2L).username("bob").status("ACTIVE").role("USER").build()));

        assertThatThrownBy(() -> service.list())
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("管理员");
    }

    @Test
    void rejectsMissingSession() {
        WorkspaceContext.clear();
        assertThatThrownBy(() -> service.list())
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("未登录");
    }

    @Test
    void createsUserWithEncodedPasswordAndWorkspaceMembership() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(SysUser.class))).thenAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(2L);
            return user;
        });
        when(workspaceService.currentWorkspaceId()).thenReturn(7L);
        when(memberRepository.existsByWorkspaceIdAndUserId(7L, 2L)).thenReturn(false);

        UserView view = service.create(new CreateUserRequest("alice", "Passw0rd!", "Alice", null));

        assertThat(view.username()).isEqualTo("alice");
        assertThat(view.nickname()).isEqualTo("Alice");
        assertThat(view.role()).isEqualTo("USER");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.self()).isFalse();

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).save(captor.capture());
        String hash = captor.getValue().getPasswordHash();
        assertThat(hash).startsWith("$2").isNotEqualTo("Passw0rd!");
        assertThat(encoder.matches("Passw0rd!", hash)).isTrue();

        verify(memberRepository).save(any(SysWorkspaceMember.class));
        verify(auditLogService).record(eq("USER_CREATED"), eq("USER"), eq("alice"), anyString());
    }

    @Test
    void rejectsDuplicateUsername() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
        assertThatThrownBy(() -> service.create(new CreateUserRequest("admin", "Passw0rd!", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void rejectsWeakPassword() {
        assertThatThrownBy(() -> service.create(new CreateUserRequest("alice", "123", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8-128");
    }

    @Test
    void cannotDisableSelf() {
        assertThatThrownBy(() -> service.update(1L, new UpdateUserRequest(null, null, "DISABLED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前登录的管理员");
    }

    @Test
    void resetsPasswordWithFreshHash() {
        SysUser target = SysUser.builder()
                .id(2L).username("alice").nickname("Alice")
                .passwordHash("old-hash").status("ACTIVE").role("USER")
                .createdAt(Instant.now()).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(SysUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.resetPassword(2L, new ResetPasswordRequest("NewPassw0rd"));

        assertThat(target.getPasswordHash()).startsWith("$2").isNotEqualTo("old-hash");
        assertThat(encoder.matches("NewPassw0rd", target.getPasswordHash())).isTrue();
        verify(auditLogService).record(eq("USER_PASSWORD_RESET"), eq("USER"), eq("alice"), anyString());
    }

    private SysUser admin() {
        return SysUser.builder()
                .id(1L).username("admin").nickname("管理员")
                .passwordHash("admin-hash").status("ACTIVE").role("ADMIN")
                .createdAt(Instant.now()).build();
    }
}
