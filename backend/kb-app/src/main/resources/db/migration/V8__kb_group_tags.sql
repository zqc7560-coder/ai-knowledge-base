-- V8: M10.4 知识库分组与标签
ALTER TABLE knowledge_bases
  ADD COLUMN grp VARCHAR(40) NULL COMMENT '分组名(空=未分组,每用户自由文本)',
  ADD COLUMN tags VARCHAR(200) NULL COMMENT '逗号分隔标签';
