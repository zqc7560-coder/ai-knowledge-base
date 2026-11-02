package com.kb.document.parser;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT / Markdown 解析：编码自动探测（UTF-8 BOM > 严格 UTF-8 > GBK）；
 * MD 的 #..###### 标题行维护标题栈，正文段挂 titlePath（docs/01 F3）。
 */
@Component
public class PlainTextParser implements DocumentParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.*)$");
    private static final String BLOCK_SPLIT = "\\r?\\n\\s*\\r?\\n";
    private static final String SOFT_WRAP = "\\s*\\n\\s*";

    @Override
    public String supportedType() { return "txt|md|markdown"; }

    public boolean supports(String ext) {
        return ext.equals("txt") || ext.equals("md") || ext.equals("markdown");
    }

    @Override
    public ParsedDoc parse(Path file) throws ParserException {
        String content = decode(readBytes(file));
        ParsedDoc out = new ParsedDoc();
        out.setSections(new ArrayList<>());
        String[] stack = new String[7]; // md levels 1..6
        boolean md = isMarkdown(file);
        for (String block : content.split(BLOCK_SPLIT)) {
            String b = block.trim();
            if (b.isEmpty()) continue;
            if (md && !b.contains("\n")) {
                Matcher m = HEADING.matcher(b);
                if (m.matches()) {
                    int level = m.group(1).length();
                    String title = m.group(2).trim();
                    stack[level] = title;
                    for (int i = level + 1; i < stack.length; i++) stack[i] = null;
                    out.getSections().add(new ParsedDoc.Section(title, null, titlePath(stack)));
                    continue;
                }
            }
            String text = b.replaceAll(SOFT_WRAP, " ").trim();
            out.getSections().add(new ParsedDoc.Section(text, null, titlePath(stack)));
        }
        if (out.getSections().isEmpty()) throw new ParserException("文件无有效文本");
        return out;
    }

    private static boolean isMarkdown(Path f) {
        String n = f.getFileName().toString().toLowerCase();
        return n.endsWith(".md") || n.endsWith(".markdown");
    }

    private static String titlePath(String[] stack) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < stack.length; i++) if (stack[i] != null) parts.add(stack[i]);
        return parts.isEmpty() ? null : String.join(" > ", parts);
    }

    static byte[] readBytes(Path f) throws ParserException {
        try { return Files.readAllBytes(f); }
        catch (IOException e) { throw new ParserException("读取失败", e); }
    }

    /** BOM > 严格 UTF-8 > GBK。 */
    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF)
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }
}
