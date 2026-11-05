package vip.mate.agent.platform.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysWorkspaceRepository extends JpaRepository<SysWorkspace, Long> {

    List<SysWorkspace> findAllByOrderByIdAsc();
}
