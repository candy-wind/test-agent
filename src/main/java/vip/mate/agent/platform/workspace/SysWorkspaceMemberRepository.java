package vip.mate.agent.platform.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysWorkspaceMemberRepository extends JpaRepository<SysWorkspaceMember, Long> {

    List<SysWorkspaceMember> findByUserId(Long userId);

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);
}
