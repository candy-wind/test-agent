package vip.mate.agent.platform.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    Optional<SysConfig> findByWorkspaceIdAndConfigKey(Long workspaceId, String configKey);

    void deleteByWorkspaceIdAndConfigKey(Long workspaceId, String configKey);
}
