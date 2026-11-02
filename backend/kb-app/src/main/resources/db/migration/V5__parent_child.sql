-- V5: M8.1 Parent-Child 分块开关（small-to-big：命中子片、喂父窗）
ALTER TABLE knowledge_bases
  ADD COLUMN parent_child TINYINT NOT NULL DEFAULT 0 COMMENT '1=检索命中child后扩展父窗口喂模型',
  ADD COLUMN pc_span INT NOT NULL DEFAULT 2 COMMENT '父窗=同文档前后各N片';
