-- V3: M7.1 任务状态持久化——重建/迁移任务落库，重启后可见、可追溯
CREATE TABLE IF NOT EXISTS process_tasks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  type VARCHAR(16) NOT NULL COMMENT 'reindex | migrate',
  kb_id BIGINT NOT NULL,
  kb_name VARCHAR(128) NULL,
  target VARCHAR(64) NOT NULL COMMENT '存储名，migrate 形如 pgvector->milvus',
  unit_total INT NOT NULL DEFAULT 0,
  unit_done INT NOT NULL DEFAULT 0,
  state VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING | DONE | FAILED:原因',
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_kb (kb_id), KEY idx_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台任务持久化(M7.1)';
