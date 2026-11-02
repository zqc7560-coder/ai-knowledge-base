-- V7: M9.4 chunk 人工干预——禁用位 + 编辑时间戳
ALTER TABLE document_chunks ADD COLUMN disabled TINYINT NOT NULL DEFAULT 0 COMMENT '1=人工禁用,检索与问答排除';
