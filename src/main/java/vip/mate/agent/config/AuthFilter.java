package vip.mate.agent.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.agent.controller.AuthController;

import java.io.IOException;

/**
 * 鉴权过滤器：未登录时 /api/* 返回 401、其它页面重定向登录页。
 * 鉴权通过后把真实身份与工作空间写入 WorkspaceContext，供服务层做数据隔离。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuthFilter implements Filter {

    private final AuthSessionRegistry sessionRegistry;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        String token = AuthController.extractToken(request);
        AuthSessionRegistry.Session session = sessionRegistry.verify(token);
        if (session == null) {
            if (path.startsWith("/api/")) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"未登录或会话已过期\"}");
            } else {
                response.sendRedirect("/login.html");
            }
            return;
        }

        WorkspaceContext.set(new WorkspaceContext.Identity(
                session.username(), session.userId(), session.workspaceId()));
        try {
            chain.doFilter(req, res);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private boolean isPublic(String path) {
        return path.equals("/login") || path.equals("/login.html")
                || path.equals("/api/auth/login") || path.equals("/api/auth/logout")
                || path.equals("/favicon.ico")
                || path.endsWith(".ico") || path.endsWith(".css");
    }
}
