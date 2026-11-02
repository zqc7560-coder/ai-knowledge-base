package com.kb.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析（PDFBox 3.x）：逐页取文本，按空行切段，段带页码（1-based）。
 * 扫描版（全文无文本）抛 ParserException -> 管道记 FAILED 提示 OCR（MVP 约定，见 docs/01 R2）。
 */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public String supportedType() { return "pdf"; }

    @Override
    public ParsedDoc parse(Path file) throws ParserException {
        ParsedDoc out = new ParsedDoc();
        out.setSections(new ArrayList<>());
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            int pages = doc.getNumberOfPages();
            for (int p = 1; p <= pages; p++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(p);
                s.setEndPage(p);
                s.setSortByPosition(true);
                String text = s.getText(doc);
                for (String block : splitBlocks(text)) {
                    out.getSections().add(new ParsedDoc.Section(block, p, null));
                }
            }
            if (out.getSections().isEmpty())
                throw new ParserException("未提取到文本：可能是扫描版 PDF，需要 OCR（当前版本暂不支持）");
        } catch (ParserException pe) {
            throw pe;
        } catch (IOException e) {
            throw new ParserException("PDF 读取失败: " + e.getClass().getSimpleName(), e);
        }
        return out;
    }

    /** 空行分块；块内软换行合并（中文跨行直接接、西文按空格接）。 */
    static List<String> splitBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        for (String b : text.split("\\n\\s*\\n")) {
            String cleaned = joinLines(b).trim();
            if (cleaned.isEmpty()) continue;
            // 纯页码/页眉页脚残片丢弃：短且含数字
            if (cleaned.length() < 8 && cleaned.matches(".*\\d.*")) continue;
            blocks.add(cleaned);
        }
        return blocks;
    }

    /** 合并软换行：两行都是西文补空格，涉及中文则直接拼接。 */
    static String joinLines(String block) {
        String[] lines = block.trim().split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.replaceAll("[ \\t]+", " ").trim();
            if (line.isEmpty()) continue;
            if (sb.length() == 0) { sb.append(line); continue; }
            char prev = sb.charAt(sb.length() - 1);
            char cur = line.charAt(0);
            boolean cjk = isCjk(prev) || isCjk(cur);
            sb.append(cjk ? "" : " ").append(line);
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }
}
