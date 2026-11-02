package com.kb.document.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX 解析（POI）：识别 Heading1/2/3 维护标题栈，段落挂标题路径（供 chunk 元数据与引用展示）。
 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public String supportedType() { return "docx"; }

    @Override
    public ParsedDoc parse(Path file) throws ParserException {
        ParsedDoc out = new ParsedDoc();
        out.setSections(new ArrayList<>());
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            String[] stack = new String[4]; // levels 1..3
            // M9.5：改遍历 BodyElements——段落与表格按文档顺序交错。
            // 旧实现只读 getParagraphs()，表格内容整张丢弃（实测发现的真实缺陷，docs/07 记录）。
            for (var el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (text.isEmpty()) continue;
                    int level = headingLevel(p);
                    if (level > 0) {
                        stack[level] = text;
                        for (int i = level + 1; i <= 3; i++) stack[i] = null;
                    }
                    out.getSections().add(new ParsedDoc.Section(text, null, titlePath(stack)));
                } else if (el instanceof org.apache.poi.xwpf.usermodel.XWPFTable tb) {
                    String md = tableToMarkdown(tb);
                    if (!md.isBlank())
                        out.getSections().add(new ParsedDoc.Section(md, null, titlePath(stack)));
                }
            }
            // M9.2：内嵌图片进 OCR 队列（>=50KB 才值得；页码未知记 0）
            for (var pic : doc.getAllPictures()) {
                byte[] data = pic.getData();
                if (data != null && data.length >= 51200) out.addImage(0, data);
            }
            if (out.getSections().isEmpty() && out.getImages().isEmpty())
                throw new ParserException("DOCX 无文本内容");
        } catch (ParserException pe) {
            throw pe;
        } catch (Exception e) {
            throw new ParserException("DOCX 解析失败: " + e.getClass().getSimpleName(), e);
        }
        return out;
    }

    static int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) return 0;
        String s = style.toLowerCase();
        // Word 内置样式名：Heading1 / 标题 1 / Title
        if (s.startsWith("heading")) {
            try { return Math.min(3, Integer.parseInt(s.replaceAll("\\D+", ""))); }
            catch (NumberFormatException e) { return 1; }
        }
        if (s.startsWith("标题")) {
            try { return Math.min(3, Integer.parseInt(s.replaceAll("\\D+", ""))); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static String titlePath(String[] stack) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i <= 3; i++) if (stack[i] != null) parts.add(stack[i]);
        return parts.isEmpty() ? null : String.join(" > ", parts);
    }

    /** DOCX 表格 → Markdown 表（首行当表头；单元格内换行折成空格，防破坏 md 表结构）。 */
    static String tableToMarkdown(org.apache.poi.xwpf.usermodel.XWPFTable tb) {
        StringBuilder sb = new StringBuilder();
        var rows = tb.getRows();
        if (rows.isEmpty()) return "";
        final String NL = chr(10);
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell c : rows.get(r).getTableCells())
                cells.add(c.getText().replaceAll(WS_ONE, " ").trim().replace("|", "/"));
            if (cells.stream().allMatch(String::isEmpty)) continue;
            sb.append("| ").append(String.join(" | ", cells)).append(" |").append(NL);
            if (r == 0) {
                StringBuilder sep = new StringBuilder("|");
                for (int x = 0; x < cells.size(); x++) sep.append(" --- |");
                sb.append(sep).append(NL);
            }
        }
        String md = sb.toString().trim();
        return md.isEmpty() ? "" : "【表格】" + NL + md;
    }

    private static final String WS_ONE = "[" + chr(9) + chr(10) + chr(13) + " ]+";
    private static String chr(int c) { return String.valueOf((char) c); }
}
