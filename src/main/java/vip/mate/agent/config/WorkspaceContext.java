package vip.mate.agent.config;

/**
 * 当前请求的身份与工作空间上下文。由 AuthFilter 在鉴权通过后写入，
 * 请求结束时清理；异步任务通过 {@link #wrap(Runnable)} 显式传递。
 */
public final class WorkspaceContext {

    public record Identity(String username, Long userId, Long workspaceId) {
    }

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private WorkspaceContext() {
    }

    public static void set(Identity identity) {
        CURRENT.set(identity);
    }

    public static Identity get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在提交到线程池之前调用，确保异步链路仍能拿到正确的空间。 */
    public static Runnable wrap(Runnable task) {
        Identity captured = CURRENT.get();
        return () -> {
            if (captured != null) {
                CURRENT.set(captured);
            }
            try {
                task.run();
            } finally {
                CURRENT.remove();
            }
        };
    }
}
