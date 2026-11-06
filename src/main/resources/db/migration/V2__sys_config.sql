-- ============================================================
-- P2 配置存储：外部数据源等非模型类密钥统一存库并加密
-- ============================================================

CREATE TABLE sys_config (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT       NOT NULL REFERENCES sys_workspace (id) ON DELETE CASCADE,
    config_key   VARCHAR(128) NOT NULL,
    config_value TEXT,
    description  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_sys_config_workspace_key UNIQUE (workspace_id, config_key)
);
