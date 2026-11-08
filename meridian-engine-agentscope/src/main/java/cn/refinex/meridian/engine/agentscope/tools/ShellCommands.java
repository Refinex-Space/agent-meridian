package cn.refinex.meridian.engine.agentscope.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code bash} 命令的分段解析，供命令前缀规则使用（架构设计「平台工具」{@code bash} 行）。
 *
 * <p>机制参考 Claude Code 的 bash 权限判定（claude-code-analysis {@code src/tools/BashTool/bashPermissions.ts}，只学机制、
 * 不复制代码）：复合命令按 {@code &&}、{@code ||}、{@code ;}、{@code |}、{@code |&}、{@code &}、换行拆成若干段，放行规则要求
 * 每一段都匹配，拒绝规则只要有一段匹配；含命令替换、进程替换、反引号、子 shell 括号或未闭合引号的命令看不出实际会执行什么，
 * 视为"不可分析"，任何内容级规则都不匹配它。本实现是平台自己的简化版本：不做 AST 解析，只识别上述结构；判断不了的一律归为
 * 不可分析，宁可多一次审批，也不误放行。
 *
 * <p>分段保留原文（引号不去掉），只把引号外的连续空白折叠为一个空格；重定向（{@code >}、{@code 2>&1}、{@code &>}）属于所在的段。
 */
public final class ShellCommands {

    /** 剥离后再比较拒绝规则的包装命令：它们执行后面的命令，{@code nice rm -rf x} 也要命中 {@code rm:*} 拒绝规则。 */
    static final Set<String> WRAPPERS =
            Set.of("env", "nice", "nohup", "time", "timeout", "command", "exec", "xargs", "sudo");

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");

    /** 包装命令的数值参数：优先级、时长（{@code 10}、{@code 1.5}、{@code 10s}）、个数。 */
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+(\\.[0-9]+)?[smhd]?");

    private ShellCommands() {}

    /**
     * 解析一条命令。
     *
     * @param command 命令原文
     * @return 分段，或不可分析
     */
    public static Parsed parse(String command) {
        Objects.requireNonNull(command, "command");
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int length = command.length();
        for (int i = 0; i < length; i++) {
            char c = command.charAt(i);
            char next = i + 1 < length ? command.charAt(i + 1) : 0;
            if (quote == '\'') {
                current.append(c);
                if (c == '\'') {
                    quote = 0;
                }
                continue;
            }
            if (c == '\\') {
                if (next == '\n') {
                    // 续行：反斜杠加换行不是分隔
                    i++;
                    continue;
                }
                current.append(c);
                if (next != 0) {
                    current.append(next);
                    i++;
                }
                continue;
            }
            if (c == '`' || (c == '$' && next == '(')) {
                return new Opaque("command substitution");
            }
            if (quote == '"') {
                current.append(c);
                if (c == '"') {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> {
                    quote = c;
                    current.append(c);
                }
                case '(', ')' -> {
                    return new Opaque(
                            previous(current) == '<' || previous(current) == '>'
                                    ? "process substitution"
                                    : "subshell or grouping");
                }
                case ';', '\n' -> {
                    flush(current, segments);
                    if (c == ';' && next == ';') {
                        i++;
                    }
                }
                case '|' -> {
                    if (previous(current) == '>') {
                        // >| 是强制覆盖的重定向
                        current.append(c);
                    } else {
                        flush(current, segments);
                        if (next == '|' || next == '&') {
                            i++;
                        }
                    }
                }
                case '&' -> {
                    char before = previous(current);
                    if (next == '&') {
                        flush(current, segments);
                        i++;
                    } else if (next == '>' || before == '>' || before == '<') {
                        // &> 、>&、<& 是重定向
                        current.append(c);
                    } else {
                        // 后台执行：前面是一段完整的命令
                        flush(current, segments);
                    }
                }
                case ' ', '\t', '\r' -> {
                    if (!current.isEmpty() && previous(current) != ' ') {
                        current.append(' ');
                    }
                }
                default -> current.append(c);
            }
        }
        if (quote != 0) {
            return new Opaque("unterminated quote");
        }
        flush(current, segments);
        return new Segments(List.copyOf(segments));
    }

    /**
     * 判断一段命令是否匹配命令前缀规则的内容：{@code prefix:*} 匹配等于 {@code prefix} 或以 {@code prefix} 加空格开头的段，
     * 不带 {@code :*} 时要求整段相同。
     *
     * @param ruleContent 规则内容
     * @param segment 一段命令
     * @return 匹配时为 true
     */
    public static boolean segmentMatches(String ruleContent, String segment) {
        String rule = collapse(ruleContent.strip());
        if (rule.endsWith(":*")) {
            String prefix = rule.substring(0, rule.length() - 2).strip();
            return !prefix.isEmpty() && (segment.equals(prefix) || segment.startsWith(prefix + " "));
        }
        return segment.equals(rule);
    }

    /**
     * 一段命令在比较拒绝与询问规则时的各种形式：原文，以及去掉开头的环境变量赋值与包装命令（{@link #WRAPPERS}，连同紧随的选项
     * 与数值参数，例如 {@code nice -n 5}、{@code timeout -k 5 10}）之后的命令。
     *
     * @param segment 一段命令
     * @return 原文在前的各种形式
     */
    static List<String> denyForms(String segment) {
        List<String> forms = new ArrayList<>();
        forms.add(segment);
        List<String> words = new ArrayList<>(List.of(segment.split(" ")));
        boolean stripped = false;
        while (!words.isEmpty()) {
            String head = words.getFirst();
            if (ASSIGNMENT.matcher(head).matches()) {
                words.removeFirst();
                stripped = true;
                continue;
            }
            if (!WRAPPERS.contains(head)) {
                break;
            }
            words.removeFirst();
            stripped = true;
            while (!words.isEmpty()
                    && (words.getFirst().startsWith("-")
                            || NUMERIC.matcher(words.getFirst()).matches())) {
                words.removeFirst();
            }
        }
        if (stripped && !words.isEmpty()) {
            forms.add(String.join(" ", words));
        }
        return forms;
    }

    private static String collapse(String text) {
        return text.replaceAll("[ \t]+", " ");
    }

    private static char previous(StringBuilder current) {
        return current.isEmpty() ? 0 : current.charAt(current.length() - 1);
    }

    private static void flush(StringBuilder current, List<String> segments) {
        String segment = current.toString().strip();
        if (!segment.isEmpty()) {
            segments.add(segment);
        }
        current.setLength(0);
    }

    /** 解析结果。 */
    public sealed interface Parsed permits Segments, Opaque {}

    /**
     * 可以分析的命令。
     *
     * @param segments 各段命令，按出现顺序；空命令为空列表
     */
    public record Segments(List<String> segments) implements Parsed {}

    /**
     * 看不出实际会执行什么的命令，任何内容级规则都不匹配它。
     *
     * @param reason 原因（英文，给日志与审批说明用）
     */
    public record Opaque(String reason) implements Parsed {}
}
