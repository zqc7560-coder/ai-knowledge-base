package com.kb.document.parser;

import java.io.InputStream;
import java.nio.file.Path;

/** 解析策略接口：每种文件类型一个实现（docs/01 F3）。 */
public interface DocumentParser {
    /** 支持的扩展名（小写，不含点），如 "pdf"。 */
    String supportedType();

    /** 解析失败抛 ParserException，由管道层记录 FAILED 状态与原因。 */
    ParsedDoc parse(Path file) throws ParserException;

    default ParsedDoc parse(InputStream in) throws ParserException {
        throw new ParserException("该解析器需要文件路径");
    }
}
