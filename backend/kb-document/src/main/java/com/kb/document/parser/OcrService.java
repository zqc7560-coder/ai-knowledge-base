package com.kb.document.parser;

import com.kb.rag.ai.AiClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描版 PDF 的 OCR 服务（M6）：PDFBox 渲染页面 → qwen-vl-ocr 视觉模型识别 → 回填 ParsedDoc。
 *
 * <h3>为什么用视觉大模型而不是装本地 PaddleOCR</h3>
 * ① 本机纪律禁往 C 盘装东西，且 PaddleOCR 模型文件+Python 依赖链庞大；
 * ② 上游已验证 qwen-vl-ocr 对中文混排识别准确（"智能网关 XG-999 校准说明/校准周期为 180 天"
 *    整图全对），走现成 AI 通道零新增运维；
 * ③ 密钥链路复用 AiClientFactory，安全审计面不变（Bearer 仍只在一处）。
 * 成本可控：OCR 页数有上限（kb.doc.ocr-max-pages），超限记 FAILED 防一页超大书打爆额度。
 *
 * <h3>为什么按页调用而不是整档一张图</h3>
 * 视觉模型单次输入尺寸有限，整档拼接会爆像素；按页天然对齐"块带页码"的元数据设计，
 * 识别结果逐页转 Section，切片/引用定位自动继承页码。
 */
@Slf4j
@Service
public class OcrService {

    private static final String INSTRUCTION =
            "识别这张图片中的所有文字内容，保持原有段落与顺序，直接输出纯文本，不要任何解释或Markdown标记。";

    private final AiClientFactory ai;
    private final PdfParser pdfParser;
    private final boolean enabled;
    private final int maxPages;

    public OcrService(AiClientFactory ai, PdfParser pdfParser,
                      @Value("${kb.doc.ocr-enabled:true}") boolean enabled,
                      @Value("${kb.doc.ocr-max-pages:30}") int maxPages) {
        this.ai = ai;
        this.pdfParser = pdfParser;
        this.enabled = enabled;
        this.maxPages = maxPages;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 对 needsOcr 的 PDF 执行逐页识别。返回补齐 sections 的 ParsedDoc。
     * 失败（超限/上游连续异常）抛 ParserException，由管道记 FAILED+原因。
     */
    public ParsedDoc ocr(Path file, ParsedDoc probe) throws ParserException {
        int pages = probe.getPageCount();
        if (!enabled) throw new ParserException("未提取到文本：疑似扫描版 PDF（共 " + pages + " 页），且服务端 OCR 开关关闭");
        if (pages > maxPages)
            throw new ParserException("扫描版 PDF 共 " + pages + " 页，超过 OCR 上限 " + maxPages + " 页，请拆分后重传");
        List<ParsedDoc.Section> sections = new ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            byte[] png = pdfParser.renderPagePng(file, p);
            String text;
            try {
                text = ai.visionOcr(png, INSTRUCTION).text();   // token 用量已在工厂内归因(M7.2)
            } catch (Exception e) {
                throw new ParserException("OCR 第 " + p + " 页识别失败: " + e.getMessage(), e);
            }
            for (String block : PdfParser.splitBlocks(text)) {
                sections.add(new ParsedDoc.Section(block, p, null));
            }
            log.info("ocr page {}/{} chars={}", p, pages, text == null ? 0 : text.length());
        }
        if (sections.isEmpty()) throw new ParserException("OCR 完成但未识别出任何文字");
        ParsedDoc out = new ParsedDoc();
        out.setSections(sections);
        out.setPageCount(pages);
        return out;
    }
}
