package vip.mate.agent.platform.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.agent.config.WorkspaceContext;
import vip.mate.agent.platform.workspace.SysAuditLog;
import vip.mate.agent.platform.workspace.SysAuditLogRepository;

import java.time.Instant;

/**
 * 审计写入。审计失败不能影响主流程，因此内部吞掉异常并只记日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogRepository repository;

    public void record(String action, String resourceType, String resourceId, String detail) {
        try {
            WorkspaceContext.Identity identity = WorkspaceContext.get();
            repository.save(SysAuditLog.builder()
                    .workspaceId(identity == null ? null : identity.workspaceId())
                    .userId(identity == null ? null : identity.userId())
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .detailJson(detail == null ? null : detail.substring(0, Math.min(detail.length(), 2000)))
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("[audit] 写入审计日志失败: action={} resource={}", action, resourceId, e);
        }
    }
}
