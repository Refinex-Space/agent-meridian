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
 *
 * <p>同样归为不可分析的（R1 安全审查，ADR-0041）：ANSI-C 与本地化引用（{@code $'…'}、{@code $"…"}，引号规则与普通引号不同，
 * {@code echo $'\'' ; rm x ; echo \'} 会被误读成一段）、复合命令（{@code { …; }}、{@code if}/{@code for}/{@code while} 等关键字、
 * {@code !}）、命令名含变量展开、执行字符串的命令（{@code eval}、{@code source}、{@code .}、{@code bash -c} 等）。命令前缀规则
 * 是审批层的尽力而为，不是隔离手段，沙箱才是边界。
 */
public final class ShellCommands {

    /** 剥离后再比较拒绝规则的包装命令：它们执行后面的命令，{@code nice rm -rf x} 也要命中 {@code rm:*} 拒绝规则。 */
    static final Set<String> WRAPPERS =
            Set.of("env", "nice", "nohup", "time", "timeout", "command", "exec", "xargs", "sudo");

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");

    /** 复合命令的关键字与保留字：出现在命令位置时，这一段不是一条简单命令。 */
    private static final Set<String> RESERVED = Set.of(
            "{",
            "}",
            "!",
            "if",
            "then",
            "else",
            "elif",
            "fi",
            "for",
            "while",
            "until",
            "do",
            "done",
            "case",
            "esac",
            "select",
            "function",
            "coproc");

    /** 把参数当作命令字符串执行的命令：后面实际执行什么看不出来。 */
    private static final Set<String> EVALUATORS = Set.of("eval", "source", ".");

    /** 带 {@code -c} 时执行命令字符串的 shell。 */
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh", "ash", "fish");

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
            if (c == '$' && (next == '\'' || next == '"')) {
                return new Opaque("ANSI-C or locale quoting");
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
        for (String segment : segments) {
            String reason = notSimple(segment);
            if (reason != null) {
                return new Opaque(reason);
            }
        }
        return new Segments(List.copyOf(segments));
    }

    /** 一段不是可按前缀判断的简单命令时返回原因，否则为 null。 */
    private static String notSimple(String segment) {
        List<String> words = commandWords(segment);
        if (words.isEmpty()) {
            return null;
        }
        String head = words.getFirst();
        if (head.startsWith("{") || RESERVED.contains(head)) {
            return "compound command";
        }
        if (head.contains("$")) {
            return "command name from expansion";
        }
        String name = commandName(head);
        if (EVALUATORS.contains(name)) {
            return "evaluated string";
        }
        if (SHELLS.contains(name) && words.stream().skip(1).anyMatch(word -> word.matches("-[A-Za-z]*c[A-Za-z]*"))) {
            return "shell command string";
        }
        return null;
    }

    /** 去掉开头的环境变量赋值与包装命令（连同其选项与数值参数）之后的词，第一个词是实际执行的命令。 */
    private static List<String> commandWords(String segment) {
        List<String> words = new ArrayList<>(List.of(segment.split(" ")));
        while (!words.isEmpty()) {
            String head = words.getFirst();
            if (ASSIGNMENT.matcher(head).matches()) {
                words.removeFirst();
                continue;
            }
            if (!WRAPPERS.contains(commandName(head))) {
                break;
            }
            words.removeFirst();
            while (!words.isEmpty()
                    && (words.getFirst().startsWith("-")
                            || NUMERIC.matcher(words.getFirst()).matches())) {
                words.removeFirst();
            }
        }
        return words;
    }

    /** 命令名的规范形式：去掉引号与反斜杠、取路径的最后一段（{@code "rm"}、{@code \rm}、{@code /bin/rm} 都是 {@code rm}）。 */
    static String commandName(String word) {
        String bare = word.replace("'", "").replace("\"", "").replace("\\", "");
        int slash = bare.lastIndexOf('/');
        return slash >= 0 && slash < bare.length() - 1 ? bare.substring(slash + 1) : bare;
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
     * 一段命令在比较拒绝与询问规则时的各种形式：原文；去掉开头的环境变量赋值与包装命令（{@link #WRAPPERS}，连同紧随的选项
     * 与数值参数，例如 {@code nice -n 5}、{@code timeout -k 5 10}）之后的命令；再把命令名换成规范形式（{@link #commandName}）
     * 之后的命令，{@code /bin/rm -rf x}、{@code \rm x}、{@code "rm" x} 也要命中 {@code rm:*}。
     *
     * @param segment 一段命令
     * @return 原文在前、不重复的各种形式
     */
    static List<String> denyForms(String segment) {
        List<String> forms = new ArrayList<>();
        forms.add(segment);
        List<String> words = commandWords(segment);
        if (words.isEmpty()) {
            return forms;
        }
        String stripped = String.join(" ", words);
        if (!forms.contains(stripped)) {
            forms.add(stripped);
        }
        List<String> named = new ArrayList<>(words);
        named.set(0, commandName(words.getFirst()));
        String normalized = String.join(" ", named);
        if (!forms.contains(normalized)) {
            forms.add(normalized);
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
