package com.kb.document.parser;

import java.util.List;

/**
 * 解析产物：按文档结构排序的段落列表（含页码/标题路径元数据）。
 *
 * <h3>needsOcr 标志（M6）</h3>
 * PDF 全文无文字层（扫描版）时，PdfParser 不再直接抛异常终止管道，而是返回
 * needsOcr=true 的空产物——由管道层（DocumentService）决定：OCR 开关开着就渲染
 * 页面走视觉模型补文本，没开则记 FAILED 给出人话原因。这样"是否 OCR"成为
 * 管道策略而非解析器职责，解析器保持纯格式转换。
 */
public class ParsedDoc {
    private List<Section> sections;
    private boolean needsOcr;
    /** 需要 OCR 时的页数（PdfParser 探测出），管道据此逐页渲染识别。 */
    private int pageCount;

    public static class Section {
        private String text;
        private Integer pageNo;      // PDF 页码（1-based），其他格式为 null
        private String titlePath;    // DOCX 标题层级 / MD 标题路径

        public Section(String text, Integer pageNo, String titlePath) {
            this.text = text;
            this.pageNo = pageNo;
            this.titlePath = titlePath;
        }

        public String getText() { return text; }
        public Integer getPageNo() { return pageNo; }
        public String getTitlePath() { return titlePath; }
    }

    public List<Section> getSections() { return sections; }
    public void setSections(List<Section> sections) { this.sections = sections; }
    public boolean isNeedsOcr() { return needsOcr; }
    public void setNeedsOcr(boolean needsOcr) { this.needsOcr = needsOcr; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
}
