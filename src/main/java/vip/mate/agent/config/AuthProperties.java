package vip.mate.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录与安全相关配置。口令不再写在 application.yml 里：
 * 优先 AUTH_PASSWORD_HASH（BCrypt），其次 AUTH_PASSWORD（明文，启动时哈希），
 * 两者都没有时生成一次性随机口令并打印在启动日志中。
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private String username = "admin";

    /** 明文口令（仅用于本地/兼容部署，生产建议用 passwordHash） */
    private String password = "";

    /** BCrypt 哈希，最高优先级 */
    private String passwordHash = "";

    private long tokenTtlHours = 24;

    /** HTTPS 部署时设为 true；纯 HTTP 部署必须保持 false，否则浏览器不会回传 Cookie */
    private boolean cookieSecure = false;

    private int maxFailedAttempts = 5;

    private int lockoutMinutes = 15;
}
