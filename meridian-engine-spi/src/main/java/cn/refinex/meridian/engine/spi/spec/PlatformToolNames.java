package cn.refinex.meridian.engine.spi.spec;

import java.util.Set;

/**
 * 平台工具的名字，与架构设计「平台工具」表一一对应。
 *
 * <p>智能体版本的工具集只能从这里选；业务方声明的自定义工具不能与这些名字重名，也不能以
 * {@link #MCP_PREFIX} 开头（架构设计「平台工具」中的保留工具名）。harness 仍然注册的工具名由
 * {@code meridian-engine-agentscope} 在装配后从 Toolkit 读出，不在这里列出。
 */
public final class PlatformToolNames {

    /** 读文件，规则内容为路径 glob。 */
    public static final String READ_FILE = "read_file";

    /** 列目录，规则内容为路径 glob。 */
    public static final String LIST_FILES = "list_files";

    /** 按 glob 查找文件，规则内容为路径 glob。 */
    public static final String GLOB_FILES = "glob_files";

    /** 按内容搜索文件，规则内容为路径 glob。 */
    public static final String GREP_FILES = "grep_files";

    /** 写文件，规则内容为路径 glob。 */
    public static final String WRITE_FILE = "write_file";

    /** 编辑文件，规则内容为路径 glob。 */
    public static final String EDIT_FILE = "edit_file";

    /** 在会话沙箱内执行命令，规则内容为命令前缀；只在 {@code sandbox} 档提供。 */
    public static final String BASH = "bash";

    /** 联网搜索，后端按租户配置。 */
    public static final String WEB_SEARCH = "web_search";

    /** 抓取网页，规则内容为域名；必须做 SSRF 防护。 */
    public static final String WEB_FETCH = "web_fetch";

    /** 知识库检索，规则内容为知识库 ID。 */
    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    /** 拉起子智能体，规则内容为子智能体名。 */
    public static final String SPAWN_SUBAGENT = "spawn_subagent";

    /** MCP 工具名的前缀：{@code mcp__{server}__{tool}}。 */
    public static final String MCP_PREFIX = "mcp__";

    /** 全部平台工具名。 */
    public static final Set<String> ALL = Set.of(
            READ_FILE,
            LIST_FILES,
            GLOB_FILES,
            GREP_FILES,
            WRITE_FILE,
            EDIT_FILE,
            BASH,
            WEB_SEARCH,
            WEB_FETCH,
            KNOWLEDGE_SEARCH,
            SPAWN_SUBAGENT);

    private PlatformToolNames() {}

    /**
     * 判断一个名字是否为 MCP 工具名。
     *
     * @param toolName 工具名
     * @return 以 {@link #MCP_PREFIX} 开头时为 true
     */
    public static boolean isMcpTool(String toolName) {
        return toolName.startsWith(MCP_PREFIX);
    }
}
