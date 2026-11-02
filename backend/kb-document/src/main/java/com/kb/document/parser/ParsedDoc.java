package com.kb.document.parser;

import lombok.Data;

import java.util.List;

/** 解析产物：按文档结构排序的段落列表（含页码/标题路径元数据）。 */
@Data
public class ParsedDoc {
    private List<Section> sections;

    @Data
    public static class Section {
        private String text;
        private Integer pageNo;      // PDF 页码（1-based），其他格式为 null
        private String titlePath;    // DOCX 标题层级 / MD 标题路径

        public Section(String text, Integer pageNo, String titlePath) {
            this.text = text;
            this.pageNo = pageNo;
            this.titlePath = titlePath;
        }
    }

    /** 拼接全部段落文本（空段落过滤）。 */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (Section s : sections) {
            if (s.text != null && !s.text.isBlank()) sb.append(s.text).append("\n\n");
        }
        return sb.toString();
    }
}
