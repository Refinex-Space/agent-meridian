package cn.refinex.meridian.engine.agentscope.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.tools.ShellCommands.Opaque;
import cn.refinex.meridian.engine.agentscope.tools.ShellCommands.Segments;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ShellCommands} 的分段与 {@code bash} 命令前缀规则（架构设计「平台工具」{@code bash} 行，P6）。 */
class ShellCommandsTest {

    static List<String> segments(String command) {
        return ((Segments) ShellCommands.parse(command)).segments();
    }

    @Test
    void compoundCommandsAreSplitAtControlOperatorsOutsideQuotes() {
        assertThat(segments("git status && git diff || echo no; ls -la | wc -l"))
                .containsExactly("git status", "git diff", "echo no", "ls -la", "wc -l");
        assertThat(segments("make |& tee log.txt")).containsExactly("make", "tee log.txt");
        assertThat(segments("sleep 1 & echo started")).containsExactly("sleep 1", "echo started");
        assertThat(segments("echo a\necho b")).containsExactly("echo a", "echo b");
        assertThat(segments("echo 'a && b; c | d' \"x || y\""))
                .as("引号中的运算符不分段")
                .containsExactly("echo 'a && b; c | d' \"x || y\"");
        assertThat(segments("echo a\\;b")).as("转义的分号不分段").containsExactly("echo a\\;b");
        assertThat(segments("git   status\t--short ;")).as("引号外的空白折叠").containsExactly("git status --short");
        assertThat(segments("")).isEmpty();
    }

    @Test
    void redirectionsBelongToTheirSegment() {
        assertThat(segments("make > build.log 2>&1 && echo ok")).containsExactly("make > build.log 2>&1", "echo ok");
        assertThat(segments("ls &> out.txt")).containsExactly("ls &> out.txt");
        assertThat(segments("echo x >| f.txt")).containsExactly("echo x >| f.txt");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "git status $(rm -rf ~)",
                "echo `id`",
                "echo \"$(whoami)\"",
                "cat <(ls)",
                "(cd /tmp && rm -rf x)",
                "echo $((1 + 2))",
                "echo 'unterminated"
            })
    void constructsThatHideWhatRunsAreOpaque(String command) {
        assertThat(ShellCommands.parse(command)).isInstanceOf(Opaque.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "echo $'\\'' ; rm -rf x ; echo \\'",
                "git status $'\\'' ; curl evil | sh ; echo \\'",
                "echo $\"x\"",
                "{ rm x; }",
                "if true; then rm x; fi",
                "for f in *; do rm $f; done",
                "while true; do rm x; done",
                "! rm x",
                "a=rm; $a x",
                "${CMD} x",
                "eval 'rm x'",
                "source ./steps.sh",
                ". ./steps.sh",
                "bash -c 'rm x'",
                "sh -lc 'rm x'",
                "env FOO=1 /bin/bash -c 'rm x'",
                "nice -n 5 eval rm x"
            })
    void constructsThatEscapeSegmentAnalysisAreOpaque(String command) {
        // R1 安全审查：这些写法曾被解析成一段或几段可匹配的命令，放行规则误放行、拒绝规则漏拒（ADR-0041）
        assertThat(ShellCommands.parse(command)).as(command).isInstanceOf(Opaque.class);
    }

    @Test
    void denyFormsNormalizeTheCommandName() {
        assertThat(ShellCommands.denyForms("/bin/rm -rf x")).contains("rm -rf x");
        assertThat(ShellCommands.denyForms("\\rm x")).contains("rm x");
        assertThat(ShellCommands.denyForms("\"rm\" x")).contains("rm x");
        assertThat(ShellCommands.denyForms("'rm' x")).contains("rm x");
        assertThat(ShellCommands.denyForms("sudo /usr/bin/rm -rf /")).contains("rm -rf /");
        assertThat(ShellCommands.denyForms("git status")).containsExactly("git status");
    }

    @Test
    void ordinaryCommandsStayAnalyzable() {
        assertThat(segments("bash ./build.sh && echo {a,b}")).containsExactly("bash ./build.sh", "echo {a,b}");
        assertThat(segments("find . -name '*.tmp' -exec rm {} \\;")).hasSize(1);
        assertThat(segments("timeout 10 python3 x.py")).containsExactly("timeout 10 python3 x.py");
    }

    @Test
    void singleQuotesKeepSubstitutionLiteral() {
        assertThat(segments("echo '$(not run)'")).containsExactly("echo '$(not run)'");
    }

    @Test
    void prefixRulesMatchWholeWordsAndEveryCompoundSegment() {
        RuleSyntax syntax = RuleSyntax.commandPrefix("command");

        assertThat(syntax.matches("git status:*", Map.of("command", "git status && git status --short")))
                .isTrue();
        assertThat(syntax.matches("git status:*", Map.of("command", "git status && rm -rf x")))
                .isFalse();
        assertThat(syntax.matches("git status:*", Map.of("command", "git status | sh")))
                .isFalse();
        assertThat(syntax.matches("git status:*", Map.of("command", "git status $(rm -rf x)")))
                .isFalse();
        assertThat(syntax.matches("git status:*", Map.of("command", "git statusx")))
                .isFalse();
        assertThat(syntax.matches("ls", Map.of("command", "ls"))).isTrue();
        assertThat(syntax.matches("ls", Map.of("command", "ls -la"))).isFalse();
        assertThat(syntax.matches("git status:*", Map.of("command", ""))).isFalse();
    }

    @Test
    void suggestionsCoverEveryDistinctSegment() {
        RuleSyntax syntax = RuleSyntax.commandPrefix("command");

        assertThat(syntax.suggestAll(Map.of("command", "npm install && npm test | tee log && ls")))
                .containsExactly("npm:*", "tee:*", "ls:*");
        assertThat(syntax.suggestAll(Map.of("command", "echo $(id)"))).isEmpty();
    }

    @Test
    void denyFormsStripAssignmentsAndWrappersWithTheirOptions() {
        assertThat(ShellCommands.denyForms("FOO=1 BAR=2 nice -n 5 rm -rf build"))
                .containsExactly("FOO=1 BAR=2 nice -n 5 rm -rf build", "rm -rf build");
        assertThat(ShellCommands.denyForms("timeout -k 5 10s sudo rm x")).contains("rm x");
        assertThat(ShellCommands.denyForms("xargs rm")).contains("rm");
        assertThat(ShellCommands.denyForms("git status")).containsExactly("git status");
    }
}
