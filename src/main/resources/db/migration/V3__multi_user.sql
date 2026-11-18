-- ============================================================
-- 多账号：为 sys_user 增加全局角色（ADMIN 可管理账号，USER 为普通成员）
-- 已有部署升级时，原有账号统一提升为 ADMIN，避免升级后无人可管理账号。
-- ============================================================

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';

UPDATE sys_user SET role = 'ADMIN' WHERE role = 'USER';

CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user (status);
