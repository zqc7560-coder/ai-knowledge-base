package com.kb.document.parser;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * XLSX 解析（M9.1）：每 Sheet 一个 Section（页码=Sheet 序），
 * 行展平为 "表头单元格 | 单元格 …"，空行跳过；合并单元格取左上值其余留空（POI 默认行为，够用）。
 * 第一行按表头对待拼进每行（否则切片后数据行失去列语义——检索"某列等于X"无法命中）。
 * 不支持 .xls（旧二进制，POI 需 hssf 另行处理且 FileValidator 白名单只放 xlsx）。
 */
@Component
public class XlsxParser implements DocumentParser {

    @Override
    public String supportedType() { return "xlsx"; }

    @Override
    public ParsedDoc parse(Path file) throws ParserException {
        try (InputStream in = new FileInputStream(file.toFile());
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            ParsedDoc out = new ParsedDoc();
            List<ParsedDoc.Section> sections = new ArrayList<>();
            int pageNo = 0;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                XSSFSheet sheet = wb.getSheetAt(s);
                pageNo++;
                List<String> header = new ArrayList<>();
                List<String> lines = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    short last = row.getLastCellNum();
                    for (int c = 0; c < last; c++) {
                        String v = fmt.formatCellValue(row.getCell(c)).replaceAll("\\s+", " ").trim();
                        cells.add(v);
                    }
                    cells.removeIf(String::isEmpty);
                    if (cells.isEmpty()) continue;
                    if (header.isEmpty()) { header = cells; continue; }
                    if (lines.size() >= 5000) { lines.add("…（该 Sheet 行数超过 5000，余下截断）"); break; }
                    lines.add(String.join(" | ", cells));
                }
                if (!lines.isEmpty()) {
                    String body = "【表头】" + String.join(" | ", header) + "\n" + String.join("\n", lines);
                    sections.add(new ParsedDoc.Section(body, pageNo, sheet.getSheetName()));
                }
            }
            if (sections.isEmpty()) throw new ParserException("XLSX 中未提取到数据行");
            out.setSections(sections);
            out.setPageCount(pageNo);
            return out;
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("XLSX 解析失败: " + e.getMessage(), e);
        }
    }
}
