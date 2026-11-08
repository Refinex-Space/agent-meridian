package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台安全规则：五层合成中优先级最高的 deny，任何下层规则都不能放开（架构设计「权限判定」第 1 层）。
 *
 * <ul>
 *   <li>写受保护路径：智能体的说明与能力定义（{@code AGENTS.md}、{@code skills/}、{@code subagents/}、{@code tools.json}）以及长期
 *       记忆（{@code MEMORY.md}）只由平台与管理员维护，智能体不能改写自己的行为约束。对写文件、编辑文件两个工具生效。
 *   <li>读凭据类文件：私钥、证书、{@code .env}、各类客户端的凭据文件，防止智能体把凭据读进上下文再经模型或工具外泄。对读文件与
 *       按内容搜索两个工具生效（列目录、按名查找只暴露文件名）。
 * </ul>
 *
 * <p>规则内容是相对工作区根的路径 glob，由文件工具的 {@code matchRule} 解释（T10a）：{@code **} 匹配零或多级目录，所以
 * {@code **}{@code /.env} 同时匹配根目录与任意子目录中的 {@code .env}。{@code bash} 只有命令前缀规则，不能表达"写某个路径"，
 * 沙箱中对这些路径的保护由 T12a 的 bash 规则与只读挂载负责。
 *
 * <p>线程安全：不可变。
 */
public final class PlatformSafetyRules {

    /** 智能体不能写入的工作区路径（架构设计「权限判定」）。 */
    public static final List<String> PROTECTED_WRITE_PATHS =
            List.of("AGENTS.md", "skills/**", "subagents/**", "tools.json", "MEMORY.md");

    /** 智能体不能读取内容的凭据类文件。 */
    public static final List<String> CREDENTIAL_PATHS = List.of(
            "**/.env",
            "**/.env.*",
            "**/*.pem",
            "**/*.key",
            "**/*.p12",
            "**/*.pfx",
            "**/*.jks",
            "**/id_rsa*",
            "**/id_ecdsa*",
            "**/id_ed25519*",
            "**/.ssh/**",
            "**/.aws/credentials",
            "**/.netrc",
            "**/.npmrc",
            "**/.pypirc",
            "**/.git-credentials",
            "**/.docker/config.json",
            "**/.kube/config");

    /** 写受保护路径的工具。 */
    static final List<String> WRITE_TOOLS = List.of(PlatformToolNames.WRITE_FILE, PlatformToolNames.EDIT_FILE);

    /** 读取文件内容的工具。 */
    static final List<String> READ_TOOLS = List.of(PlatformToolNames.READ_FILE, PlatformToolNames.GREP_FILES);

    /** 全部平台安全规则，都是 {@link PolicyLayer#PLATFORM} 层的 deny。 */
    public static final List<PermissionRule> RULES = build();

    private PlatformSafetyRules() {}

    private static List<PermissionRule> build() {
        List<PermissionRule> rules = new ArrayList<>();
        for (String tool : WRITE_TOOLS) {
            for (String path : PROTECTED_WRITE_PATHS) {
                rules.add(new PermissionRule(tool, path, PermissionBehavior.DENY, PolicyLayer.PLATFORM));
            }
        }
        for (String tool : READ_TOOLS) {
            for (String path : CREDENTIAL_PATHS) {
                rules.add(new PermissionRule(tool, path, PermissionBehavior.DENY, PolicyLayer.PLATFORM));
            }
        }
        return List.copyOf(rules);
    }
}
