package cn.refinex.meridian.runtime.tooling.infra;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * 把抓到的响应体转成交给模型的文本：HTML 用 jsoup 去掉脚本、样式等不可见内容，再用 flexmark 转成 Markdown（参考
 * spring-ai-agent-utils 的 {@code SmartWebFetchTool}，同样选用 flexmark-html2md-converter）；纯文本、JSON、XML 原样返回；其他类型
 * 不支持（ADR-0035）。
 *
 * <p>线程安全：flexmark 的转换器不可变，可以共享。
 */
final class WebContentConverter {

    /** 按 Markdown 转换的媒体类型。 */
    private static final Set<String> HTML = Set.of("text/html", "application/xhtml+xml");

    /** 去掉的元素：不可见或与正文无关。 */
    private static final String INVISIBLE = "script, style, noscript, template, svg, canvas, iframe, object, embed";

    private final FlexmarkHtmlConverter converter =
            FlexmarkHtmlConverter.builder().build();

    /**
     * 媒体类型是否支持。
     *
     * @param mediaType 小写、不带参数的媒体类型
     * @return 支持时为 true
     */
    static boolean supported(String mediaType) {
        return HTML.contains(mediaType)
                || mediaType.startsWith("text/")
                || mediaType.equals("application/json")
                || mediaType.equals("application/xml")
                || mediaType.endsWith("+json")
                || mediaType.endsWith("+xml");
    }

    /**
     * 取 {@code Content-Type} 中的媒体类型。
     *
     * @param contentType 响应头的原值；可为 null
     * @return 小写、不带参数的媒体类型；缺省为 {@code application/octet-stream}
     */
    static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 转换响应体。
     *
     * @param contentType 响应头的原值
     * @param body 响应体（可能已被截断）
     * @param baseUri 页面地址，用于把相对链接转成绝对链接
     * @return 给模型看的文本
     */
    String convert(String contentType, byte[] body, String baseUri) {
        String mediaType = mediaType(contentType);
        if (!HTML.contains(mediaType)) {
            return new String(body, charset(contentType).orElse(StandardCharsets.UTF_8));
        }
        Document document = charset(contentType)
                .map(c -> Jsoup.parse(new String(body, c), baseUri))
                .orElseGet(() -> Jsoup.parse(new String(body, StandardCharsets.UTF_8), baseUri));
        document.select(INVISIBLE).remove();
        document.select("a[href]").forEach(a -> a.attr("href", a.absUrl("href")));
        String title = document.title().strip();
        String markdown = converter
                .convert(document.body() == null ? "" : document.body().html())
                .strip();
        return title.isEmpty() ? markdown : "# " + title + "\n\n" + markdown;
    }

    private static Optional<Charset> charset(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        for (String parameter : contentType.split(";")) {
            String[] pair = parameter.strip().split("=", 2);
            if (pair.length == 2 && pair[0].strip().equalsIgnoreCase("charset")) {
                try {
                    return Optional.of(Charset.forName(pair[1].strip().replace("\"", "")));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
