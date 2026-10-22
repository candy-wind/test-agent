package vip.mate.agent.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.agent.controller.AuthController;

import java.io.IOException;

/**
 * 鉴权过滤器：未登录时
 *   - /api/*  返回 401 JSON
 *   - 其他页面  重定向到 /login.html
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 放行：登录页/接口、静态登录页、favicon 等
        if (path.equals("/login") || path.equals("/login.html")
                || path.equals("/api/auth/login") || path.equals("/api/auth/logout")
                || path.startsWith("/favicon")
                || path.endsWith(".ico") || path.endsWith(".css")) {
            chain.doFilter(req, res);
            return;
        }

        String token = AuthController.extractToken(request);
        AuthController.TokenInfo info = AuthController.verify(token);
        if (info == null) {
            log.debug("鉴权失败 [{}] {} -> {}", method, path,
                    path.startsWith("/api/") ? "401" : "redirect to /login.html");
            if (path.startsWith("/api/")) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"未登录或会话已过期\"}");
            } else {
                response.sendRedirect("/login.html");
            }
            return;
        }
        log.trace("鉴权通过 [{}] {} user={}", method, path, info.getUsername());
        chain.doFilter(req, res);
    }
}