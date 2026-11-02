package com.kb.document.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PPTX 解析（M9.1）：逐"幻灯片"产出一个 Section（页码=slide 序号），
 * 形状按版式顺序取文本；表格展平成 "行 | 列 | 列" 管线文本（切片器把它当普通段落）。
 * 不支持 .ppt（二进制旧格式）——FileValidator 白名单只放 pptx，报错口径统一。
 */
@Component
public class PptxParser implements DocumentParser {

    @Override
    public String supportedType() { return "pptx"; }

    @Override
    public ParsedDoc parse(Path file) throws ParserException {
        try (InputStream in = new FileInputStream(file.toFile());
             XMLSlideShow ppt = new XMLSlideShow(in)) {
            ParsedDoc out = new ParsedDoc();
            List<ParsedDoc.Section> sections = new ArrayList<>();
            int pageNo = 0;
            for (var slide : ppt.getSlides()) {
                pageNo++;
                StringBuilder sb = new StringBuilder();
                for (XSLFShape sh : slide.getShapes()) {
                    if (sh instanceof XSLFTable tb) {
                        for (var row : tb.getRows()) {
                            List<String> cells = new ArrayList<>();
                            row.getCells().forEach(c -> {
                                String t = c.getText().replaceAll("\\s+", " ").trim();
                                if (!t.isEmpty()) cells.add(t);
                            });
                            if (!cells.isEmpty()) sb.append(String.join(" | ", cells)).append('\n');
                        }
                    } else if (sh instanceof XSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) sb.append(t.trim()).append('\n');
                    }
                }
                String body = sb.toString().trim();
                if (!body.isEmpty())
                    sections.add(new ParsedDoc.Section(body, pageNo, "第" + pageNo + "页"));
            }
            if (sections.isEmpty()) throw new ParserException("PPTX 中未提取到文字（可能是纯图片演示文稿）");
            out.setSections(sections);
            out.setPageCount(pageNo);
            return out;
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("PPTX 解析失败: " + e.getMessage(), e);
        }
    }
}
