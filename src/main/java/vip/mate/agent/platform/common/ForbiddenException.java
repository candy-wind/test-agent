package vip.mate.agent.platform.common;

/** 已登录但权限不足（例如普通成员访问用户管理接口）。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
