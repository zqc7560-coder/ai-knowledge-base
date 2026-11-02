import { marked } from "marked";
import DOMPurify from "dompurify";

/**
 * 助手答案 Markdown 渲染（docs/02 M4.5 前端部分）。
 * 安全：marked 产出先过 DOMPurify —— 防 XSS（密钥规范 §3.5 同源要求）。
 * 引用编号 [1] [2][3] 转成可点击上标。
 */
export function renderMd(src: string): string {
  const html = marked.parse(src || "", { async: false }) as string;
  const clean = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
  // [n] -> <sup class="cite-ref" data-n="n">n</sup>
  return clean.replace(/\[(\d+)\]/g, (_m, n) =>
    `<sup class="cite-ref" data-n="${n}">${n}</sup>`);
}
