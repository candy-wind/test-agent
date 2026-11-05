package vip.mate.agent.platform.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工作空间上下文。
 * P0 阶段登录体系仍沿用原有单管理员 Token，因此默认使用第一个工作空间；
 * 接入 Spring Security 后改为从会话上下文解析。
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
        return defaultWorkspace().getId();
    }
}
