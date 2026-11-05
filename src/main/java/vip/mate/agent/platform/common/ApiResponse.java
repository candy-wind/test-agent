package vip.mate.agent.platform.common;

/**
 * 平台接口统一响应。
 * 仅用于新增的 /api/v1/platform 接口，不影响原有接口返回结构。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
