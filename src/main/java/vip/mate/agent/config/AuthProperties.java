package vip.mate.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录与安全相关配置。
 * 管理员口令固定为默认值 123456（由 PlatformBootstrap 保证），不再通过环境变量配置；
 * 其他账号由管理员在「用户管理」页面创建与维护。
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private String username = "admin";

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
