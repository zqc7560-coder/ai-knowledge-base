package com.kb.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析（PDFBox 3.x）：逐页取文本，按空行分块，块带页码（1-based）。
 *
 * <h3>M6 变化：扫描版不再直接报错</h3>
 * 全文提取为 0 块时判定为"扫描版"，返回 needsOcr=true + 页数，由管道层
 * （DocumentService）根据 OCR 开关决定"逐页渲染→视觉模型识别"还是记 FAILED。
 * 好处：解析器保持单一职责（只做格式→文本），是否走昂贵的 OCR 属管道策略；
 * 页面渲染也放在 OcrService（它才持有 PDFBox 渲染所需的 PDDocument 生命周期）。
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
            out.setPageCount(pages);
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
            if (out.getSections().isEmpty()) {
                // 扫描版信号：交管道层决策，不再抛异常
                out.setNeedsOcr(true);
            }
        } catch (IOException e) {
            throw new ParserException("PDF 读取失败: " + e.getClass().getSimpleName(), e);
        }
        return out;
    }

    /**
     * 渲染第 page 页为 PNG（OCR 用）。DPI=150：视觉模型读图的性价比点——
     * 再高对 qwen-vl 精度提升有限但 base64 体积翻倍（请求体上限/带宽都受影响），再低小字丢失。
     * 单独方法而非塞进 parse()：渲染是 OCR 支路才需要的昂贵操作，常规文字版 PDF 不触发。
     */
    public byte[] renderPagePng(Path file, int page) throws ParserException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            java.awt.image.BufferedImage img = r.renderImageWithDPI(Math.min(page, doc.getNumberOfPages()) - 1, 150,
                    org.apache.pdfbox.rendering.ImageType.RGB);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ParserException("PDF 渲染失败 p" + page + ": " + e.getClass().getSimpleName(), e);
        }
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
