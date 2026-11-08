package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.SandboxCommand;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@code bash}：在会话沙箱中执行 shell 命令（架构设计「平台工具」：规则内容为命令前缀，副作用等级 {@code non_idempotent}，仅
 * {@code sandbox} 档）。
 *
 * <p>命令经 {@code RoutedSandboxFilesystem.execute} 在沙箱内执行，工作目录是工作区根 {@code /workspace}，每次调用一个新的 shell；
 * 平台用 coreutils 的 {@code timeout} 包住命令来落实超时（AgentScope 2.0.4 的扩展不把超时传给执行服务），超时退出码为 124。
 * 输出（stdout 加 stderr）超过 {@link #MAX_OUTPUT_CHARS} 时保留头尾。每条命令执行后经 {@link ToolLedgerPort#recordSandboxCommand}
 * 写审计。
 *
 * <p>权限：放行与询问规则经 {@link RuleSyntax#commandPrefix} 要求复合命令的每一段都匹配；拒绝规则与询问规则另由
 * {@link #checkPermissions} 按"任一段匹配"判定，{@code true && rm -rf x} 也会被 {@code bash(rm:*)} 拒绝。
 *
 * <p>线程安全：无状态。
 */
public final class BashTool extends PlatformTool {

    /**
     * 一次输出的上限，单位字符。低于平台工具输出的约定上限 50000 与工具结果卸载阈值 80000：命令输出常常很长（构建日志、
     * {@code cat} 大文件），单次调用不能占满上下文；超出时保留开头与结尾各一半，错误信息多在结尾。
     */
    static final int MAX_OUTPUT_CHARS = 30_000;

    /** {@code timeout} 发出 TERM 之后再等多久发 KILL，单位秒。 */
    static final int KILL_GRACE_SECONDS = 5;

    /** {@code timeout} 超时时的退出码。 */
    static final int TIMEOUT_EXIT_CODE = 124;

    static final String DESCRIPTION = """
            Run a shell command with bash inside this session's sandbox (a Linux container) and return its output.

            Use it to run scripts and programs, inspect or convert files, and anything else that needs \
            a shell. Prefer read_file, write_file, edit_file, list_files, glob_files and grep_files for plain \
            file reading and editing.

            Behavior:
            - Each call starts a fresh shell in the workspace root (/workspace); cd, exported variables and \
            background processes do not persist between calls. Chain dependent steps with && in one command.
            - Paths are relative to the workspace root. Put deliverable files under outputs/: they are copied \
            to platform storage at the end of the turn so the user can download them.
            - Python 3 (with python-docx, python-pptx, openpyxl, pandas, pypdf and matplotlib) and Node.js are \
            installed. \
            The network is restricted; do not rely on downloading packages.
            - The command is killed after timeout_seconds (default %d, at most %d); exit code 124 means it timed out.
            - Output is stdout followed by stderr. Output longer than %d characters keeps the beginning and the end.
            - The result ends with the exit code when it is not 0.
            """;

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    /** {@link #decide} 没有结论时的哨兵，交回权限引擎继续按规则判定。 */
    private static final PermissionDecision PASSTHROUGH = PermissionDecision.passthrough(PlatformToolNames.BASH);

    private final Settings settings;
    private final ToolLedgerPort ledger;

    /**
     * 创建工具。
     *
     * @param services 平台服务
     * @param settings 超时设置
     */
    public BashTool(ToolServices services, Settings settings) {
        super(
                new Spec(
                        PlatformToolNames.BASH,
                        DESCRIPTION.formatted(
                                settings.defaultTimeout().toSeconds(),
                                settings.maxTimeout().toSeconds(),
                                MAX_OUTPUT_CHARS),
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "command",
                                        Map.of("type", "string", "description", "The bash command to run."),
                                        "timeout_seconds",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "minimum",
                                                1,
                                                "maximum",
                                                settings.maxTimeout().toSeconds(),
                                                "description",
                                                "Seconds before the command is killed; default "
                                                        + settings.defaultTimeout()
                                                                .toSeconds()
                                                        + ".")),
                                "required",
                                List.of("command")),
                        SideEffect.NON_IDEMPOTENT,
                        RuleSyntax.commandPrefix("command"),
                        false),
                services);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ledger = services.ledger();
    }

    /**
     * {@link PlatformToolProvider#with(String, java.util.function.BiFunction)} 用的工厂：只在 {@code sandbox} 档创建（架构设计
     * 「平台工具」{@code bash} 行）。
     *
     * @param settings 超时设置
     * @return 工厂；{@code none} 档返回 null
     */
    public static BiFunction<ToolServices, AgentProfile, PlatformTool> factory(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        return (services, profile) ->
                profile.environment().tier() == EnvironmentSpec.Tier.SANDBOX ? new BashTool(services, settings) : null;
    }

    /**
     * 在权限引擎的规则判定之外补上复合命令的语义（参考 Claude Code 的机制：拒绝要比放行更难绕过）：
     *
     * <ul>
     *   <li>拒绝、询问：任一段命中即拒绝或询问，包装命令与环境变量赋值剥离后再比较（{@code FOO=1 nice rm -rf x} 命中
     *       {@code bash(rm:*)}）。权限引擎只按"一条规则匹配所有段"判定规则，{@code git status && rm -rf x} 会漏过。
     *   <li>放行：每一段都被某一条放行规则覆盖即放行，不同段可以由不同规则覆盖（{@code git status && git diff} 被
     *       {@code bash(git status:*)} 与 {@code bash(git diff:*)} 共同放行）。
     *   <li>看不出会执行什么的命令（命令替换等）在存在内容级拒绝规则时询问。
     * </ul>
     *
     * <p>询问的理由带 {@code safety}：2.0.4 的权限引擎只对这类工具判定不再查放行规则。{@code DONT_ASK} 模式下把询问改为拒绝，
     * 与平台对无人审批执行的改写一致（ADR-0031）。本方法在权限引擎的拒绝与询问规则之后、放行规则之前被调用。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        if (!(toolInput.get("command") instanceof String command)) {
            return super.checkPermissions(toolInput, context);
        }
        List<PermissionRule> denies = contentRules(context.getDenyRules());
        List<PermissionRule> asks = contentRules(context.getAskRules());
        List<PermissionRule> allows = contentRules(context.getAllowRules());
        if (denies.isEmpty() && asks.isEmpty() && allows.isEmpty()) {
            return super.checkPermissions(toolInput, context);
        }
        return switch (ShellCommands.parse(command)) {
            case ShellCommands.Segments parsed ->
                Mono.just(decide(parsed.segments(), denies, asks, allows, context))
                        .flatMap(decision -> decision == PASSTHROUGH
                                ? super.checkPermissions(toolInput, context)
                                : Mono.just(decision));
            case ShellCommands.Opaque opaque ->
                denies.isEmpty()
                        ? super.checkPermissions(toolInput, context)
                        : Mono.just(askOrDeny(
                                context, "The command cannot be checked against deny rules (" + opaque.reason() + ")"));
        };
    }

    private static PermissionDecision decide(
            List<String> segments,
            List<PermissionRule> denies,
            List<PermissionRule> asks,
            List<PermissionRule> allows,
            PermissionContextState context) {
        for (String segment : segments) {
            PermissionRule deny = firstMatch(denies, ShellCommands.denyForms(segment));
            if (deny != null) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.DENY)
                        .message("Command segment '" + segment + "' is denied by rule bash(" + deny.ruleContent() + ")")
                        .decisionReason("deny rule matches a command segment")
                        .build();
            }
        }
        for (String segment : segments) {
            PermissionRule ask = firstMatch(asks, ShellCommands.denyForms(segment));
            if (ask != null) {
                return askOrDeny(
                        context,
                        "Command segment '" + segment + "' requires approval by rule bash(" + ask.ruleContent() + ")");
            }
        }
        if (segments.size() > 1
                && segments.stream().allMatch(segment -> firstMatch(allows, List.of(segment)) != null)) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.ALLOW)
                    .message("Every command segment is allowed by a bash rule")
                    .decisionReason("allow rules cover every command segment")
                    .build();
        }
        return PASSTHROUGH;
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String command = call.string("command");
        if (command == null || command.isBlank()) {
            throw new ToolInputException("Missing required parameter 'command'.");
        }
        AbstractFilesystem filesystem = call.filesystem();
        if (!(filesystem instanceof AbstractSandboxFilesystem sandbox)) {
            throw new ToolInputException("bash is only available in sandbox environments.");
        }
        int timeout = timeoutSeconds(call);
        String wrapped = "timeout -k " + KILL_GRACE_SECONDS + " " + timeout + " bash -c " + shellQuote(command);
        long started = System.nanoTime();
        ExecuteResponse response =
                sandbox.execute(call.runtimeContext(), wrapped, timeout + KILL_GRACE_SECONDS + KILL_GRACE_SECONDS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        int exitCode = response.exitCode() == null ? -1 : response.exitCode();
        boolean timedOut = exitCode == TIMEOUT_EXIT_CODE;
        audit(call, command, exitCode, timedOut, elapsed);
        if (exitCode < 0) {
            // 执行服务或连接出错，命令可能没有执行
            return ToolOutput.error(
                    "The sandbox could not run the command: " + truncate(nullToEmpty(response.output())));
        }
        return ToolOutput.ok(render(nullToEmpty(response.output()), exitCode, timedOut, timeout))
                .with(
                        "meridian_exec",
                        Map.of("exit_code", exitCode, "timed_out", timedOut, "duration_ms", elapsed.toMillis()));
    }

    /**
     * 输出的呈现：截断后的输出，非 0 退出码与超时附在结尾。
     *
     * @param output stdout 加 stderr
     * @param exitCode 退出码
     * @param timedOut 是否超时
     * @param timeout 超时秒数
     * @return 给模型看的文本
     */
    static String render(String output, int exitCode, boolean timedOut, int timeout) {
        StringBuilder text = new StringBuilder(output.isBlank() ? "(no output)" : truncate(output.stripTrailing()));
        if (timedOut) {
            text.append("\n\n[The command timed out after ").append(timeout).append(" seconds and was killed.]");
        }
        if (exitCode != 0) {
            text.append("\n\nExit code: ").append(exitCode);
        }
        return text.toString();
    }

    /**
     * 超过 {@link #MAX_OUTPUT_CHARS} 时保留开头与结尾各一半，中间标出省略的字符数。
     *
     * @param output 输出
     * @return 截断后的输出
     */
    static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        int half = MAX_OUTPUT_CHARS / 2;
        int omitted = output.length() - 2 * half;
        return output.substring(0, half)
                + "\n\n[... " + omitted + " characters omitted ...]\n\n"
                + output.substring(output.length() - half);
    }

    /**
     * 单引号包住任意文本，供 {@code bash -c} 使用。
     *
     * @param text 文本
     * @return 引用后的文本
     */
    static String shellQuote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private int timeoutSeconds(ToolCall call) {
        int requested;
        try {
            requested = call.integer(
                    "timeout_seconds", (int) settings.defaultTimeout().toSeconds());
        } catch (IllegalArgumentException e) {
            throw new ToolInputException("Parameter 'timeout_seconds' must be an integer.");
        }
        long max = settings.maxTimeout().toSeconds();
        if (requested < 1 || requested > max) {
            throw new ToolInputException("Parameter 'timeout_seconds' must be between 1 and " + max + ".");
        }
        return requested;
    }

    private void audit(ToolCall call, String command, int exitCode, boolean timedOut, Duration elapsed) {
        try {
            ledger.recordSandboxCommand(new SandboxCommand(
                            call.platform().threadId(), call.toolUseId(), command, exitCode, timedOut, elapsed))
                    .contextWrite(PlatformContexts.write(call.platform()))
                    .block();
        } catch (RuntimeException e) {
            // 命令已经执行，审计写入失败不能让模型以为没有执行而重试；记 ERROR 由告警跟进
            log.error(
                    "沙箱命令的审计写入失败 toolUseId={} sessionId={}",
                    call.toolUseId(),
                    call.platform().sessionId(),
                    e);
        }
    }

    private List<PermissionRule> contentRules(Map<String, List<PermissionRule>> rules) {
        return rules.getOrDefault(getName(), List.of()).stream()
                .filter(rule ->
                        rule.ruleContent() != null && !rule.ruleContent().isEmpty())
                .toList();
    }

    private static PermissionRule firstMatch(List<PermissionRule> rules, List<String> forms) {
        for (PermissionRule rule : rules) {
            for (String form : forms) {
                if (ShellCommands.segmentMatches(rule.ruleContent(), form)) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static PermissionDecision askOrDeny(PermissionContextState context, String message) {
        PermissionBehavior behavior =
                context.getMode() == PermissionMode.DONT_ASK ? PermissionBehavior.DENY : PermissionBehavior.ASK;
        return PermissionDecision.builder()
                .behavior(behavior)
                .message(message)
                .decisionReason("safety check: " + message)
                .build();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * {@code bash} 的超时设置。
     *
     * @param defaultTimeout 不指定时的超时；必填
     * @param maxTimeout 允许指定的最大超时，不超过执行服务的命令超时上限（ADR-0036：600 秒）；必填
     */
    public record Settings(Duration defaultTimeout, Duration maxTimeout) {

        /** 默认：超时 120 秒，最多 600 秒（执行服务的上限）。 */
        public static final Settings DEFAULTS = new Settings(Duration.ofSeconds(120), Duration.ofSeconds(600));

        /**
         * 创建设置。
         *
         * @throws NullPointerException 缺少字段时
         * @throws IllegalArgumentException 默认超时不在 1 秒到最大超时之间时
         */
        public Settings {
            Objects.requireNonNull(defaultTimeout, "defaultTimeout");
            Objects.requireNonNull(maxTimeout, "maxTimeout");
            if (defaultTimeout.toSeconds() < 1 || defaultTimeout.compareTo(maxTimeout) > 0) {
                throw new IllegalArgumentException("bash 的默认超时必须在 1 秒到最大超时之间：" + defaultTimeout);
            }
        }
    }
}
