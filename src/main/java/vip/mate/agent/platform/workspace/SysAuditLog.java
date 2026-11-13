package vip.mate.agent.platform.workspace;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 配置变更与登录审计日志，对应 V1__platform_init.sql 的 sys_audit_log。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_audit_log")
public class SysAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private Long userId;

    private String action;

    private String resourceType;

    private String resourceId;

    private String detailJson;

    private Instant createdAt;
}
