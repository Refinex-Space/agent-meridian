package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.app.TenantSettings;
import cn.refinex.meridian.runtime.session.domain.SessionErrorCode;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 从开发配置目录读取智能体版本与环境的 {@link AgentConfigProvider}（T06；T15b 之后只留给开发与测试）。
 *
 * <p>目录下每个 {@code *.yaml}、{@code *.yml} 文件是一份配置，{@code kind} 字段区分种类，其余字段与 engine-spi 的类型一致
 * （snake_case，枚举取小写值）：
 * <ul>
 *   <li>{@code kind: agent_version}：一个 {@link ResolvedAgentSpec}，按 {@code agent_version_id} 查找；按 {@code agent_id}
 *       取当前版本时，同一智能体有多个版本则取 {@code agent_version_id} 最大的（平台的版本 ID 是 ULID，按发布时间有序）。
 *   <li>{@code kind: environment}：一个 {@link EnvironmentSpec}，按 {@code environment_id} 与 {@code revision} 查找；取当前修订时
 *       取 {@code revision} 最大的。
 *   <li>{@code kind: tenant_settings}（T10b）：一个租户的设置，按 {@code tenant_id} 查找；{@code system_prompt} 是租户层提示词，
 *       {@code web_search} 是联网搜索后端（{@code backend}、{@code base_url}、{@code secret_id}）。没有时租户设置为空。
 * </ul>
 * 技能（T10a）在同级的 {@code skills} 目录（默认 {@code dev-config/skills}），每个版本一个目录
 * {@code <skill_id>/v<version>/}：其中的 {@code SKILL.md} 是正文（frontmatter 的 {@code name}、{@code description} 是技能名与
 * 清单中的说明），其余文件是附带资源，路径相对于版本目录。
 *
 * <p>每次调用都重新读取目录，改了配置不必重启；只解析 YAML 的基本类型（{@link SafeConstructor}）。目录不存在时没有任何配置，
 * 查找以 {@link NotFoundException} 失败。
 *
 * <p>方法会阻塞（文件读取），响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
public final class FileAgentConfigProvider implements AgentConfigProvider {

    /** 智能体版本配置的 {@code kind}。 */
    static final String KIND_AGENT_VERSION = "agent_version";

    /** 环境配置的 {@code kind}。 */
    static final String KIND_ENVIRONMENT = "environment";

    /** 租户设置的 {@code kind}。 */
    static final String KIND_TENANT_SETTINGS = "tenant_settings";

    /** 技能文件的大小上限，单位字节：开发配置中的技能是文本，过大的文件多半是放错了。 */
    static final long MAX_SKILL_FILE_BYTES = 1024 * 1024;

    private final Path directory;
    private final Path skillsDirectory;

    /**
     * 创建配置来源，技能目录为配置目录的同级目录 {@code skills}。
     *
     * @param directory 配置目录
     */
    public FileAgentConfigProvider(Path directory) {
        this(directory, directory.toAbsolutePath().resolveSibling("skills"));
    }

    /**
     * 创建配置来源。
     *
     * @param directory 配置目录
     * @param skillsDirectory 技能目录
     */
    public FileAgentConfigProvider(Path directory, Path skillsDirectory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.skillsDirectory = Objects.requireNonNull(skillsDirectory, "skillsDirectory");
    }

    @Override
    public ResolvedAgentSpec agentVersion(String agentVersionId) {
        Map<String, Object> document = find(
                        KIND_AGENT_VERSION, doc -> agentVersionId.equals(doc.get("agent_version_id")))
                .orElseThrow(
                        () -> new NotFoundException(SessionErrorCode.AGENT_NOT_FOUND, "智能体版本不存在：" + agentVersionId));
        return EngineJson.convert(document, ResolvedAgentSpec.class);
    }

    @Override
    public EnvironmentSpec environment(String environmentId, long revision) {
        Map<String, Object> document = find(
                        KIND_ENVIRONMENT,
                        doc -> environmentId.equals(doc.get("environment_id"))
                                && doc.get("revision") instanceof Number number
                                && number.longValue() == revision)
                .orElseThrow(() -> new NotFoundException(
                        SessionErrorCode.ENVIRONMENT_NOT_FOUND, "环境不存在：" + environmentId + " 修订号 " + revision));
        return EngineJson.convert(document, EnvironmentSpec.class);
    }

    @Override
    public ResolvedAgentSpec currentAgentVersion(String agentId) {
        Map<String, Object> document = documents().stream()
                .filter(doc -> KIND_AGENT_VERSION.equals(doc.get("kind")))
                .filter(doc -> agentId.equals(doc.get("agent_id")))
                .max(Comparator.comparing(doc -> String.valueOf(doc.get("agent_version_id"))))
                .orElseThrow(() -> new NotFoundException(SessionErrorCode.AGENT_NOT_FOUND, "智能体不存在：" + agentId));
        return EngineJson.convert(document, ResolvedAgentSpec.class);
    }

    @Override
    public EnvironmentSpec currentEnvironment(String environmentId) {
        Map<String, Object> document = documents().stream()
                .filter(doc -> KIND_ENVIRONMENT.equals(doc.get("kind")))
                .filter(doc -> environmentId.equals(doc.get("environment_id")))
                .filter(doc -> doc.get("revision") instanceof Number)
                .max(Comparator.comparingLong(doc -> ((Number) doc.get("revision")).longValue()))
                .orElseThrow(
                        () -> new NotFoundException(SessionErrorCode.ENVIRONMENT_NOT_FOUND, "环境不存在：" + environmentId));
        return EngineJson.convert(document, EnvironmentSpec.class);
    }

    @Override
    public TenantSettings tenantSettings(String tenantId) {
        return find(KIND_TENANT_SETTINGS, doc -> tenantId.equals(doc.get("tenant_id")))
                .map(doc -> EngineJson.convert(doc, TenantSettings.class))
                .orElseGet(() -> TenantSettings.empty(tenantId));
    }

    @Override
    public SkillDefinition skill(String skillId, int version) {
        Path root = skillsDirectory.resolve(skillId).resolve("v" + version);
        Path skillFile = root.resolve(SkillDefinition.SKILL_FILE);
        if (!Files.isRegularFile(skillFile)) {
            throw new NotFoundException(SessionErrorCode.SKILL_NOT_FOUND, "技能不存在：" + skillId + " 版本 " + version);
        }
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.size(file) > MAX_SKILL_FILE_BYTES) {
                    throw new IllegalStateException("技能文件超过 1 MB：" + file);
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                files.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取技能目录失败：" + root, e);
        }
        String name = String.valueOf(
                frontmatter(files.get(SkillDefinition.SKILL_FILE)).getOrDefault("name", ""));
        if (name.isBlank()) {
            throw new IllegalStateException("技能的 SKILL.md 缺少 frontmatter 中的 name：" + skillFile);
        }
        return new SkillDefinition(skillId, version, name, files);
    }

    /** 解析 Markdown 开头 {@code ---} 包围的 YAML frontmatter；没有时为空映射。 */
    static Map<String, Object> frontmatter(String markdown) {
        if (!markdown.startsWith("---")) {
            return Map.of();
        }
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) {
            return Map.of();
        }
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(markdown.substring(3, end));
        if (!(parsed instanceof Map<?, ?> map)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private Optional<Map<String, Object>> find(String kind, Predicate<Map<String, Object>> matcher) {
        return documents().stream()
                .filter(doc -> kind.equals(doc.get("kind")))
                .filter(matcher)
                .findFirst();
    }

    private List<Map<String, Object>> documents() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(FileAgentConfigProvider::isYaml)
                    .sorted()
                    .map(FileAgentConfigProvider::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取开发配置目录失败：" + directory, e);
        }
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }

    private static Map<String, Object> load(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(document instanceof Map<?, ?> map)) {
                throw new IllegalStateException("开发配置文件的顶层必须是映射：" + file);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        } catch (IOException e) {
            throw new UncheckedIOException("读取开发配置文件失败：" + file, e);
        }
    }
}
