package com.kb.document.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
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
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText() == null ? "" : p.getText().trim();
                if (text.isEmpty()) continue;
                int level = headingLevel(p);
                if (level > 0) {
                    stack[level] = text;
                    for (int i = level + 1; i <= 3; i++) stack[i] = null;
                }
                String titlePath = titlePath(stack);
                out.getSections().add(new ParsedDoc.Section(text, null, titlePath));
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
}
