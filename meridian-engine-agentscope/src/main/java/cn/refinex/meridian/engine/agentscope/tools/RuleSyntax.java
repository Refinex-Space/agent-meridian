package cn.refinex.meridian.engine.agentscope.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 平台工具的权限规则内容语法：规则 {@code tool(content)} 中的 {@code content} 怎样与一次调用的入参匹配（架构设计「平台工具」表的
 * "规则内容语法"一列）。规则内容为空时匹配该工具的全部调用，由 {@link PlatformTool#matchRule} 处理，不经过这里。
 *
 * <p>每种语法同时给出"记住此决定"时建议的内容级规则（{@link #suggest}），由 {@code generateSuggestions} 交给权限引擎。
 * 实现必须是纯函数、线程安全。
 */
public interface RuleSyntax {

    /**
     * 判断一条非空的规则内容是否匹配这次调用。
     *
     * @param ruleContent 规则内容，非空
     * @param input 工具入参
     * @return 匹配时为 true
     */
    boolean matches(String ruleContent, Map<String, Object> input);

    /**
     * 针对这次调用建议的规则内容，例如路径或命令前缀。
     *
     * @param input 工具入参
     * @return 规则内容；没有合适的内容级规则时为空，退回整工具规则
     */
    Optional<String> suggest(Map<String, Object> input);

    /**
     * 没有内容级规则的工具（如 {@code web_search}）：任何非空的规则内容都不匹配。
     *
     * @return 语法
     */
    static RuleSyntax none() {
        return new RuleSyntax() {
            @Override
            public boolean matches(String ruleContent, Map<String, Object> input) {
                return false;
            }

            @Override
            public Optional<String> suggest(Map<String, Object> input) {
                return Optional.empty();
            }
        };
    }

    /**
     * 路径 glob（文件类工具）：规则内容是相对工作区根的路径 glob，按 {@link WorkspacePaths#matches} 与入参中的路径比较。
     * 入参中的路径缺省时视为工作区根；路径越出工作区时不匹配任何规则（工具执行时会拒绝它）。
     *
     * @param parameter 入参中路径参数的名字
     * @return 语法
     */
    static RuleSyntax pathGlob(String parameter) {
        Objects.requireNonNull(parameter, "parameter");
        return new RuleSyntax() {
            @Override
            public boolean matches(String ruleContent, Map<String, Object> input) {
                return path(input)
                        .map(p -> WorkspacePaths.matches(ruleContent, p))
                        .orElse(false);
            }

            @Override
            public Optional<String> suggest(Map<String, Object> input) {
                return path(input).filter(p -> !p.isEmpty());
            }

            private Optional<String> path(Map<String, Object> input) {
                Object value = input.get(parameter);
                return WorkspacePaths.normalize(value instanceof String text ? text : null);
            }
        };
    }

    /**
     * 命令前缀（{@code bash}）：规则内容 {@code prefix:*} 匹配以 {@code prefix} 开头的命令，不带 {@code :*} 时要求整条命令相同；
     * 建议的规则取命令的第一个词。
     *
     * @param parameter 入参中命令参数的名字
     * @return 语法
     */
    static RuleSyntax commandPrefix(String parameter) {
        Objects.requireNonNull(parameter, "parameter");
        return new RuleSyntax() {
            @Override
            public boolean matches(String ruleContent, Map<String, Object> input) {
                if (!(input.get(parameter) instanceof String command)) {
                    return false;
                }
                String normalized = command.strip();
                if (ruleContent.endsWith(":*")) {
                    String prefix =
                            ruleContent.substring(0, ruleContent.length() - 2).strip();
                    return normalized.equals(prefix) || normalized.startsWith(prefix + " ");
                }
                return normalized.equals(ruleContent.strip());
            }

            @Override
            public Optional<String> suggest(Map<String, Object> input) {
                if (!(input.get(parameter) instanceof String command) || command.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(command.strip().split("\\s+", 2)[0] + ":*");
            }
        };
    }

    /**
     * 域名（{@code web_fetch}）：规则内容 {@code domain:example.com} 匹配该域名及其子域名。
     *
     * @param parameter 入参中 URL 参数的名字
     * @return 语法
     */
    static RuleSyntax domain(String parameter) {
        Objects.requireNonNull(parameter, "parameter");
        String prefix = "domain:";
        return new RuleSyntax() {
            @Override
            public boolean matches(String ruleContent, Map<String, Object> input) {
                if (!ruleContent.startsWith(prefix)) {
                    return false;
                }
                String domain = ruleContent.substring(prefix.length()).strip().toLowerCase(Locale.ROOT);
                return host(input)
                        .map(h -> h.equals(domain) || h.endsWith("." + domain))
                        .orElse(false);
            }

            @Override
            public Optional<String> suggest(Map<String, Object> input) {
                return host(input).map(h -> prefix + h);
            }

            private Optional<String> host(Map<String, Object> input) {
                if (!(input.get(parameter) instanceof String url)) {
                    return Optional.empty();
                }
                try {
                    String host = java.net.URI.create(url.strip()).getHost();
                    return Optional.ofNullable(host).map(h -> h.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        };
    }

    /**
     * 资源 ID（{@code knowledge_search} 的知识库 ID、{@code spawn_subagent} 的子智能体名）：规则内容与入参中的值相同即匹配；
     * 入参是列表时，列表中的每个值都要被规则覆盖。
     *
     * @param parameter 入参中资源参数的名字
     * @return 语法
     */
    static RuleSyntax resourceId(String parameter) {
        Objects.requireNonNull(parameter, "parameter");
        return new RuleSyntax() {
            @Override
            public boolean matches(String ruleContent, Map<String, Object> input) {
                Object value = input.get(parameter);
                if (value instanceof List<?> values) {
                    return !values.isEmpty() && values.stream().allMatch(v -> ruleContent.equals(String.valueOf(v)));
                }
                return value != null && ruleContent.equals(String.valueOf(value));
            }

            @Override
            public Optional<String> suggest(Map<String, Object> input) {
                Object value = input.get(parameter);
                return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
            }
        };
    }
}
