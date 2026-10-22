package vip.mate.agent.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易登录鉴权
 * 演示用途：基于内存 Token + Cookie 会话
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${auth.username:admin}")
    private String username;

    @Value("${auth.password:admin123}")
    private String password;

    @Value("${auth.token-ttl-hours:12}")
    private long ttlHours;

    public static final String COOKIE_NAME = "auth_token";

    /** 内存中的会话表：token -> 用户信息 */
    private static final Map<String, TokenInfo> TOKENS = new ConcurrentHashMap<>();

    public static class TokenInfo {
        private final String username;
        private final Instant expiresAt;

        public TokenInfo(String username, Instant expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }

        public String getUsername() { return username; }
        public Instant getExpiresAt() { return expiresAt; }
    }

    /**
     * 检查当前会话是否已登录（前端 /login.html 自动跳转用）
     * 已登录返回 200；未登录会被 AuthFilter 拦截返回 401
     */
    @GetMapping("/check")
    public Map<String, Object> check(HttpServletRequest request) {
        String token = extractToken(request);
        TokenInfo info = verify(token);
        if (info == null) {
            throw new IllegalStateException("未登录");
        }
        return Map.of("success", true, "username", info.getUsername());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                      @RequestParam String password,
                                      HttpServletResponse response) {
        log.info("登录请求: username={}", username);
        if (!this.username.equals(username) || !this.password.equals(password)) {
            log.warn("登录失败: 用户名或密码错误, username={}", username);
            response.setStatus(401);
            return Map.of("success", false, "message", "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        TOKENS.put(token, new TokenInfo(username, Instant.now().plusSeconds(ttlHours * 3600)));

        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge((int) (ttlHours * 3600));
        response.addCookie(cookie);

        log.info("用户 {} 登录成功, token={}", username, token);
        return Map.of("success", true, "username", username);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        if (token != null) {
            TOKENS.remove(token);
            log.info("登出成功, token={}", token);
        }
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Map.of("success", true);
    }

    /** 校验 token，返回用户信息或 null */
    public static TokenInfo verify(String token) {
        if (token == null) return null;
        TokenInfo info = TOKENS.get(token);
        if (info == null) return null;
        if (Instant.now().isAfter(info.getExpiresAt())) {
            TOKENS.remove(token);
            return null;
        }
        return info;
    }

    /** 从 Cookie 或 Authorization 头中提取 token */
    public static String extractToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (COOKIE_NAME.equals(c.getName())) return c.getValue();
            }
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
}