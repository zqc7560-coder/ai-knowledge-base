-- V10: M10.6 文档级细粒度权限
ALTER TABLE documents
  ADD COLUMN restricted TINYINT NOT NULL DEFAULT 0 COMMENT '1=非全员可见,按 doc_permissions 过滤';
-- 不建新索引：V1 的 idx_doc_kb(kb_id,deleted) 已覆盖主查询前缀，restricted 是低基数列无增益

CREATE TABLE IF NOT EXISTS doc_permissions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  doc_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  perm VARCHAR(8) NOT NULL DEFAULT 'READ' COMMENT 'READ|ASK',
  granted_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_doc_user (doc_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受限文档的用户白名单(M10.6)';
