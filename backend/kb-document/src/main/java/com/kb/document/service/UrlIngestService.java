package com.kb.document.service;

import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * URL 抓正文入库（M9.6）。安全核心是 SSRF 防护——本功能会让服务端去请求用户给的任意地址，
 * 若不加限制等于把内网扫描器交给外部用户。三防线（docs/06 定的口径，全部实现）：
 *
 *   ① 协议白名单：仅 http/https（挡 file:// gopher:// jar:// 等）。
 *   ② DNS 解析后 IP 校验：拒绝 环回/私有/链路本地/组播/保留 段（挡 http://169.254.169.254 元数据、
 *      http://127.0.0.1:8080 打自己、http://10.x 内网横移）。用 InetAddress 解析出的实际 IP 判定，
 *      而非字符串匹配——挡 DNS rebinding 的第一跳。
 *   ③ 手动跟随重定向 ≤3，且每一跳都重跑 ①②（挡 302 跳到内网的经典绕过）。
 *
 * 因此禁用 HttpClient 的 AUTO_REDIRECT——自己控制每一跳的校验。
 * 正文：jsoup 提取（去 script/style/nav/footer），标题+段落拼 Markdown，上限 500KB。
 */
@Slf4j
@Service
public class UrlIngestService {

    private static final int MAX_REDIRECT = 3;
    private static final int MAX_BODY_BYTES = 500 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)      // 手动跳，每跳都校验
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record Fetched(String title, String markdown, String finalUrl) {}

    public Fetched fetch(String url) {
        String current = normalize(url);
        String lastUrl = current;
        for (int hop = 0; hop <= MAX_REDIRECT; hop++) {
            lastUrl = current;
            URI uri = safeUri(current);                 // ① + ②（含解析后 IP 校验）
            String body = request(uri);
            if (body == null)
                throw BizException.of(BizCode.PARAM_INVALID, "目标无响应体");
            if (!body.startsWith("LOCATION:"))          // 2xx：body 就是 html（request 用前缀区分 3xx）
                return toMarkdown(body, lastUrl);
            current = body.substring(9);                // 3xx：跳下一跳，回到循环顶重新过安全校验
            if (current.isBlank()) throw BizException.of(BizCode.PARAM_INVALID, "重定向缺少 Location");
            if (!current.startsWith("http")) current = uri.resolve(current).toString();
        }
        throw BizException.of(BizCode.PARAM_INVALID, "重定向次数超过 " + MAX_REDIRECT + "，疑似跳转环");
    }

    /** 请求一次：2xx→返回 html；3xx→返回 Location；其余抛错。body 大小封顶。 */
    private String request(URI uri) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "kb-ingest/1.0 (+knowledge-base)").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                String html = resp.body();
                if (html.length() > MAX_BODY_BYTES) html = html.substring(0, MAX_BODY_BYTES);
                return html;
            }
            if (code >= 300 && code < 400) {
                String loc = resp.headers().firstValue("Location").orElse("");
                return "LOCATION:" + loc;               // 前缀约定：fetch 循环识别并逐跳校验
            }
            if (code >= 300) return null;
            throw BizException.of(BizCode.PARAM_INVALID, "目标返回 HTTP " + code);
        } catch (BizException b) {
            throw b;
        } catch (Exception e) {
            throw BizException.of(BizCode.PARAM_INVALID, "抓取失败: " + e.getClass().getSimpleName());
        }
    }

    private static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://"))
            // localhost/127./10./192.168. 等裸地址默认补 http——保证仍能进 safeUri 被 IP 规则拦截，
            // 而不是被升级成 https://127.0.0.1 连不上导致断言不稳定
            u = (u.startsWith("localhost") || u.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) ? "http://" + u : "https://" + u;
        return u;
    }

    /** ①②：协议 + 解析后 IP 黑名单。 */
    private static URI safeUri(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (Exception e) { throw BizException.of(BizCode.PARAM_INVALID, "URL 非法"); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")))
            throw BizException.of(BizCode.PARAM_INVALID, "仅允许 http/https");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw BizException.of(BizCode.PARAM_INVALID, "URL 缺主机名");
        try {
            // 解析所有 A/AAAA，任一落到危险段即拒（挡多记录 DNS）
            for (InetAddress ip : InetAddress.getAllByName(host)) {
                if (ip.isLoopbackAddress() || ip.isAnyLocalAddress() || ip.isLinkLocalAddress()
                        || ip.isSiteLocalAddress() || ip.isMulticastAddress()) {
                    throw new SSRF("目标是内网/保留地址，已拦截: " + ip.getHostAddress());
                }
                // 额外兜住 0.x 与 CGNAT 100.64/10（Java 无现成判定）
                byte[] a = ip.getAddress();
                if (a.length == 4) {
                    int b0 = a[0] & 0xFF, b1 = a[1] & 0xFF;
                    if (b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127) || b0 >= 240)
                        throw new SSRF("目标是保留地址段，已拦截");
                }
            }
        } catch (SSRF s) {
            throw BizException.of(BizCode.PARAM_INVALID, s.getMessage());
        } catch (java.net.UnknownHostException e) {
            throw BizException.of(BizCode.PARAM_INVALID, "域名无法解析");
        }
        return uri;
    }

    private static final class SSRF extends RuntimeException { SSRF(String m) { super(m); } }

    /** jsoup 正文提取 → Markdown（标题层级 + 段落/列表；剔除导航噪声标签）。 */
    public static Fetched toMarkdown(String html, String finalUrl) {
        Document d = Jsoup.parse(html, finalUrl);
        d.select("script, style, noscript, svg, nav, footer, header, aside, form, iframe, .sidebar, .comment").remove();
        StringBuilder sb = new StringBuilder();
        String title = d.title() == null || d.title().isBlank() ? finalUrl : d.title().trim();
        sb.append("# ").append(title).append("\n\n> 来源：").append(finalUrl).append("\n\n");
        var main = d.selectFirst("article, main, [role=main], body");
        if (main == null) main = d.body();
        for (var node : main.select("h1,h2,h3,h4,p,li,pre")) {
            String tag = node.tagName();
            String txt = node.text().replaceAll("\\s+", " ").trim();
            if (txt.isEmpty() || txt.length() < 2) continue;
            switch (tag) {
                case "h1" -> sb.append("## ").append(txt).append("\n\n");
                case "h2" -> sb.append("### ").append(txt).append("\n\n");
                case "h3", "h4" -> sb.append("**").append(txt).append("**\n\n");
                case "li" -> sb.append("- ").append(txt).append("\n");
                case "pre" -> sb.append("```\n").append(node.wholeText().trim()).append("\n```\n\n");
                default -> sb.append(txt).append("\n\n");
            }
            if (sb.length() > MAX_BODY_BYTES) { sb.append("\n…（正文超长截断）"); break; }
        }
        return new Fetched(title, sb.toString(), finalUrl);
    }
}
