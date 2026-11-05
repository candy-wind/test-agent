-- ============================================================
-- Mate Agent Platform - P0 初始化迁移
-- 覆盖：认证/空间、模型配置、MCP、知识库、Skill/助手、对话
-- 说明：向量维度 MVP 统一为 1024，后续多维度方案见设计文档
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- 认证与空间 ----------

CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sys_workspace (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    slug       VARCHAR(64)  NOT NULL UNIQUE,
    owner_id   BIGINT REFERENCES sys_user (id),
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sys_workspace_member (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES sys_workspace (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    role         VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, user_id)
);

CREATE TABLE sys_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    workspace_id  BIGINT,
    user_id       BIGINT,
    action        VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(64),
    detail_json   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_workspace_time ON sys_audit_log (workspace_id, created_at DESC);

-- ---------- 模型配置 ----------

CREATE TABLE ai_provider (
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   BIGINT REFERENCES sys_workspace (id) ON DELETE CASCADE,
    name           VARCHAR(64)  NOT NULL,
    type           VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    base_url       VARCHAR(512) NOT NULL,
    api_key_cipher TEXT,
    headers_json   TEXT,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE ai_model (
    id                  BIGSERIAL PRIMARY KEY,
    provider_id         BIGINT       NOT NULL REFERENCES ai_provider (id) ON DELETE CASCADE,
    model_key           VARCHAR(128) NOT NULL,
    display_name        VARCHAR(128),
    capabilities_json   TEXT         NOT NULL DEFAULT '[]',
    context_window      INTEGER,
    default_params_json TEXT,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider_id, model_key)
);

CREATE TABLE ai_model_route (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES sys_workspace (id) ON DELETE CASCADE,
    purpose      VARCHAR(32) NOT NULL,
    model_id     BIGINT      NOT NULL REFERENCES ai_model (id) ON DELETE CASCADE,
    UNIQUE (workspace_id, purpose)
);

-- ---------- MCP ----------

CREATE TABLE mcp_server (
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   BIGINT REFERENCES sys_workspace (id) ON DELETE CASCADE,
    name           VARCHAR(64)  NOT NULL,
    transport      VARCHAR(32)  NOT NULL,
    command        VARCHAR(512),
    args_json      TEXT,
    url            VARCHAR(512),
    headers_json   TEXT,
    env_cipher_json TEXT,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    status         VARCHAR(32)  NOT NULL DEFAULT 'DISCONNECTED',
    last_check_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE mcp_tool_cache (
    id                BIGSERIAL PRIMARY KEY,
    server_id         BIGINT       NOT NULL REFERENCES mcp_server (id) ON DELETE CASCADE,
    tool_name         VARCHAR(128) NOT NULL,
    description       TEXT,
    input_schema_json TEXT,
    discovered_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (server_id, tool_name)
);

-- ---------- 知识库 ----------

CREATE TABLE kb_knowledge_base (
    id                 BIGSERIAL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL REFERENCES sys_workspace (id) ON DELETE CASCADE,
    name               VARCHAR(128) NOT NULL,
    description        TEXT,
    embedding_model_id BIGINT,
    chunk_size         INTEGER      NOT NULL DEFAULT 600,
    chunk_overlap      INTEGER      NOT NULL DEFAULT 80,
    retrieval_json     TEXT,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE kb_document (
    id            BIGSERIAL PRIMARY KEY,
    kb_id         BIGINT       NOT NULL REFERENCES kb_knowledge_base (id) ON DELETE CASCADE,
    workspace_id  BIGINT       NOT NULL,
    name          VARCHAR(256) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD',
    object_key    VARCHAR(512),
    mime          VARCHAR(128),
    size          BIGINT,
    checksum      VARCHAR(64),
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error_msg     TEXT,
    metadata_json TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_kb_document_kb ON kb_document (kb_id, status);

CREATE TABLE kb_chunk (
    id            BIGSERIAL PRIMARY KEY,
    kb_id         BIGINT NOT NULL,
    document_id   BIGINT NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,
    content       TEXT    NOT NULL,
    token_count   INTEGER,
    metadata_json TEXT
);

CREATE INDEX idx_kb_chunk_document ON kb_chunk (document_id, seq);

CREATE TABLE kb_embedding (
    chunk_id  BIGINT PRIMARY KEY REFERENCES kb_chunk (id) ON DELETE CASCADE,
    embedding vector(1024)
);

CREATE INDEX idx_kb_embedding_hnsw ON kb_embedding USING hnsw (embedding vector_cosine_ops);

-- ---------- Skill 与助手 ----------

CREATE TABLE skill (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT REFERENCES sys_workspace (id) ON DELETE CASCADE,
    name         VARCHAR(128) NOT NULL,
    type         VARCHAR(16)  NOT NULL,
    description  TEXT,
    manifest_json TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE skill_version (
    id                BIGSERIAL PRIMARY KEY,
    skill_id          BIGINT       NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    version           VARCHAR(32)  NOT NULL,
    content_md        TEXT,
    files_json        TEXT,
    entry_config_json TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (skill_id, version)
);

CREATE TABLE assistant (
    id            BIGSERIAL PRIMARY KEY,
    workspace_id  BIGINT NOT NULL REFERENCES sys_workspace (id) ON DELETE CASCADE,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    system_prompt TEXT,
    params_json   TEXT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE assistant_skill (
    assistant_id BIGINT NOT NULL REFERENCES assistant (id) ON DELETE CASCADE,
    skill_id     BIGINT NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    config_json  TEXT,
    PRIMARY KEY (assistant_id, skill_id)
);

CREATE TABLE assistant_kb (
    assistant_id BIGINT NOT NULL REFERENCES assistant (id) ON DELETE CASCADE,
    kb_id        BIGINT NOT NULL REFERENCES kb_knowledge_base (id) ON DELETE CASCADE,
    PRIMARY KEY (assistant_id, kb_id)
);

CREATE TABLE assistant_mcp (
    assistant_id  BIGINT NOT NULL REFERENCES assistant (id) ON DELETE CASCADE,
    mcp_server_id BIGINT NOT NULL REFERENCES mcp_server (id) ON DELETE CASCADE,
    PRIMARY KEY (assistant_id, mcp_server_id)
);

-- ---------- 对话 ----------

CREATE TABLE chat_session (
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   BIGINT       NOT NULL REFERENCES sys_workspace (id) ON DELETE CASCADE,
    user_id        BIGINT,
    assistant_id   BIGINT,
    title          VARCHAR(256),
    model_snapshot VARCHAR(128),
    params_json    TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT      NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    role              VARCHAR(16) NOT NULL,
    content           TEXT,
    tool_calls_json   TEXT,
    tool_call_id      VARCHAR(128),
    model             VARCHAR(128),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    citations_json    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_session ON chat_message (session_id, id);

CREATE TABLE chat_attachment (
    id         BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    object_key VARCHAR(512) NOT NULL,
    mime       VARCHAR(128),
    size       BIGINT
);
