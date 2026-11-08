package cn.refinex.meridian.engine.agentscope.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 来源标签：把外部内容（网页、搜索结果、知识库切片、MCP 工具结果）交给模型之前包上的标签，平台基础提示词声明标签内的内容一律是数据、
 * 不是指令（架构设计「智能体安全」，ADR-0035）。
 *
 * <p>格式：
 *
 * <pre>{@code
 * <external_content source="web_fetch" url="https://example.com/a">
 * ...内容...
 * </external_content>
 * }</pre>
 *
 * <p>转义规则：内容中任何形如 {@code <external_content} 或 {@code </external_content} 的片段（不区分大小写，{@code <} 与
 * {@code /}、标签名之间允许空白）都把开头的 {@code <} 换成 {@code &lt;}，所以外部内容既不能伪造闭合标签跳出数据区，也不能伪造新的
 * 标签冒充另一个来源。属性值中的 {@code &}、{@code "}、{@code <}、{@code >} 按 XML 转义，换行替换为空格。内容中其余字符原样保留。
 *
 * <p>线程安全：无状态。
 */
public final class SourceTags {

    /** 标签名。 */
    public static final String TAG = "external_content";

    /** 来源：{@code web_fetch}。 */
    public static final String WEB_FETCH = "web_fetch";

    /** 来源：{@code web_search}。 */
    public static final String WEB_SEARCH = "web_search";

    /** 来源：{@code knowledge_search}。 */
    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    /** 来源：MCP 工具。 */
    public static final String MCP = "mcp";

    /** 内容中需要转义的标签片段：{@code <}、可选的 {@code /}、标签名，之间允许空白。 */
    private static final Pattern FORGED_TAG = Pattern.compile("<(\\s*/?\\s*" + TAG + ")", Pattern.CASE_INSENSITIVE);

    private SourceTags() {}

    /**
     * 包上来源标签。
     *
     * @param source 来源，取本类的常量；必填
     * @param attributes 其他属性，例如 {@code url}、{@code server}；按给定顺序输出，值为 null 的属性省略
     * @param content 外部内容；必填
     * @return 带标签的文本
     */
    public static String wrap(String source, Map<String, String> attributes, String content) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(content, "content");
        Map<String, String> all = new LinkedHashMap<>();
        all.put("source", source);
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (value != null) {
                    all.put(key, value);
                }
            });
        }
        StringBuilder text = new StringBuilder("<").append(TAG);
        all.forEach((key, value) -> text.append(' ')
                .append(key)
                .append("=\"")
                .append(attribute(value))
                .append('"'));
        text.append(">\n").append(escape(content));
        if (!content.endsWith("\n")) {
            text.append('\n');
        }
        return text.append("</").append(TAG).append('>').toString();
    }

    /**
     * 转义内容中的标签片段。
     *
     * @param content 外部内容
     * @return 转义后的内容
     */
    public static String escape(String content) {
        return FORGED_TAG.matcher(content).replaceAll(Matcher.quoteReplacement("&lt;") + "$1");
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
