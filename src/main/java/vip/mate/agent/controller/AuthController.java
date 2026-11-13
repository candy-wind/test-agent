package vip.mate.agent.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.agent.config.AuthProperties;
import vip.mate.agent.config.AuthSessionRegistry;
import vip.mate.agent.config.LoginRateLimiter;
import vip.mate.agent.platform.common.AuditLogService;
import vip.mate.agent.platform.workspace.SysUser;
import vip.mate.agent.platform.workspace.SysUserRepository;
import vip.mate.agent.platform.workspace.SysWorkspaceMember;
import vip.mate.agent.platform.workspace.SysWorkspaceMemberRepository;
import vip.mate.agent.platform.workspace.WorkspaceService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 登录鉴权：账号来自 sys_user，口令用 BCrypt 校验（由 PlatformBootstrap 初始化）。
 * 会话是服务端内存 Token + HttpOnly Cookie，Token 不写入日志、不返回给前端脚本。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String COOKIE_NAME = "auth_token";

    /** 用户不存在时也做一次等价的 BCrypt 运算，避免通过响应时间枚举账号 */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("invalid-password");

    private final AuthProperties properties;
    private final AuthSessionRegistry sessionRegistry;
    private final LoginRateLimiter rateLimiter;
    private final SysUserRepository userRepository;
    private final SysWorkspaceMemberRepository memberRepository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 检查当前会话是否已登录（前端 login.html 自动跳转用） */
    @GetMapping("/check")
    public Map<String, Object> check(HttpServletRequest request, HttpServletResponse response) {
        AuthSessionRegistry.Session session = sessionRegistry.verify(extractToken(request));
        if (session == null) {
            response.setStatus(401);
            return Map.of("success", false, "message", "未登录或会话已过期");
        }
        return Map.of("success", true, "username", session.username());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam String password,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        String account = username == null ? "" : username.trim();
        String key = account.toLowerCase(Locale.ROOT);
        if (rateLimiter.isLocked(key)) {
            log.warn("[auth] 登录被限速: username={} retryAfter={}s", account, rateLimiter.lockedSeconds(key));
            response.setStatus(429);
            return Map.of("success", false,
                    "message", "登录失败次数过多，请 " + rateLimiter.lockedSeconds(key) + " 秒后重试");
        }

        SysUser user = userRepository.findByUsername(account).orElse(null);
        String hash = user == null ? DUMMY_HASH : user.getPasswordHash();
        boolean authenticated = user != null
                && "ACTIVE".equalsIgnoreCase(user.getStatus())
                && hash != null && !hash.isBlank()
                && passwordEncoder.matches(password == null ? "" : password, hash);

        if (!authenticated) {
            rateLimiter.recordFailure(key, properties.getMaxFailedAttempts(), properties.getLockoutMinutes());
            auditLogService.record("LOGIN_FAILED", "AUTH", account, "账号或口令错误");
            log.warn("[auth] 登录失败: username={} remote={}", account, clientIp(request));
            response.setStatus(401);
            return Map.of("success", false, "message", "用户名或密码错误");
        }

        rateLimiter.reset(key);
        Long workspaceId = resolveWorkspaceId(user.getId());
        long ttl = Math.max(1, properties.getTokenTtlHours());
        String token = sessionRegistry.issue(account, user.getId(), workspaceId, ttl);

        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, ttl * 3600).toString());
        auditLogService.record("LOGIN_SUCCESS", "AUTH", account, "登录成功");
        log.info("[auth] 用户登录成功: username={}", account);
        return Map.of("success", true, "username", account);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        AuthSessionRegistry.Session session = sessionRegistry.verify(token);
        sessionRegistry.invalidate(token);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        if (session != null) {
            auditLogService.record("LOGOUT", "AUTH", session.username(), "退出登录");
        }
        return Map.of("success", true);
    }

    /** 从 Cookie 或 Authorization 头中提取 token（供 AuthFilter 复用） */
    public static String extractToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    private Long resolveWorkspaceId(Long userId) {
        List<SysWorkspaceMember> members = memberRepository.findByUserId(userId);
        if (!members.isEmpty() && members.get(0).getWorkspaceId() != null) {
            return members.get(0).getWorkspaceId();
        }
        try {
            return workspaceService.defaultWorkspaceId();
        } catch (Exception e) {
            log.warn("[auth] 无法解析用户工作空间: userId={}", userId);
            return null;
        }
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
