package vip.mate.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录与安全相关配置。口令不写在 application.yml 里：
 * 优先 AUTH_PASSWORD_HASH（BCrypt），其次 AUTH_PASSWORD（明文，启动时哈希），
 * 两者都没有时使用固定默认口令 123456（不生成随机口令）。
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

    /**
     * 首次启动可批量创建的账号，格式：user:password[:role]，多个用英文逗号分隔。
     * 只创建不存在的用户名，不覆盖已有账号的口令；口令中不要包含 : 和 ,。
     */
    private String initialUsers = "";
}
