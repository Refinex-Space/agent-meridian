package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *   <li>{@code kind: agent_version}：一个 {@link ResolvedAgentSpec}，按 {@code agent_version_id} 查找。
 *   <li>{@code kind: environment}：一个 {@link EnvironmentSpec}，按 {@code environment_id} 与 {@code revision} 查找。
 * </ul>
 * 每次调用都重新读取目录，改了配置不必重启；只解析 YAML 的基本类型（{@link SafeConstructor}）。目录不存在时没有任何配置，
 * 查找以 {@link NotFoundException} 失败。
 *
 * <p>方法会阻塞（文件读取），响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
public final class FileAgentConfigProvider implements AgentConfigProvider {

    /** 智能体版本配置的 {@code kind}。 */
    static final String KIND_AGENT_VERSION = "agent_version";

    /** 环境配置的 {@code kind}。 */
    static final String KIND_ENVIRONMENT = "environment";

    private final Path directory;

    /**
     * 创建配置来源。
     *
     * @param directory 配置目录
     */
    public FileAgentConfigProvider(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public ResolvedAgentSpec agentVersion(String agentVersionId) {
        Map<String, Object> document = find(
                        KIND_AGENT_VERSION, doc -> agentVersionId.equals(doc.get("agent_version_id")))
                .orElseThrow(() -> new NotFoundException("开发配置中没有智能体版本：" + agentVersionId));
        return EngineJson.convert(document, ResolvedAgentSpec.class);
    }

    @Override
    public EnvironmentSpec environment(String environmentId, long revision) {
        Map<String, Object> document = find(
                        KIND_ENVIRONMENT,
                        doc -> environmentId.equals(doc.get("environment_id"))
                                && doc.get("revision") instanceof Number number
                                && number.longValue() == revision)
                .orElseThrow(() -> new NotFoundException("开发配置中没有环境：" + environmentId + " 修订号 " + revision));
        return EngineJson.convert(document, EnvironmentSpec.class);
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
