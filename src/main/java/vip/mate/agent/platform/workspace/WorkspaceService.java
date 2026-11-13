package vip.mate.agent.platform.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.agent.config.WorkspaceContext;

/**
 * 工作空间上下文：优先使用 AuthFilter 写入的当前登录用户所属空间，
 * 定时任务 / 启动初始化等无请求上下文的场景才回退到默认空间。
 * 服务层统一通过这里取空间，禁止信任前端传入的 workspaceId。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final SysWorkspaceRepository workspaceRepository;

    public SysWorkspace defaultWorkspace() {
        return workspaceRepository.findAllByOrderByIdAsc().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("尚未初始化工作空间，请检查 platform.bootstrap"));
    }

    public Long defaultWorkspaceId() {
        WorkspaceContext.Identity identity = WorkspaceContext.get();
        if (identity != null && identity.workspaceId() != null) {
            return identity.workspaceId();
        }
        return defaultWorkspace().getId();
    }

    /** 语义更明确的别名，供新代码使用。 */
    public Long currentWorkspaceId() {
        return defaultWorkspaceId();
    }
}
