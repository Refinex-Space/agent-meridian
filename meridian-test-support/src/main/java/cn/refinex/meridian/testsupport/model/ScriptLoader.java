package cn.refinex.meridian.testsupport.model;

import cn.refinex.meridian.testsupport.model.ModelScript.HttpError;
import cn.refinex.meridian.testsupport.model.ModelScript.Step;
import cn.refinex.meridian.testsupport.model.ModelScript.ToolCall;
import cn.refinex.meridian.testsupport.model.ModelScript.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 读取 YAML 脚本。格式见模块 {@code CLAUDE.md}：
 *
 * <pre>
 * name: tool-once
 * description: 调用一次工具后回答
 * steps:
 *   - tool_calls:
 *       - name: lookup_order
 *         arguments: {order_id: o-1001}
 *   - text: 订单 o-1001 已发货。
 *     chunk_size: 4
 *     delay_ms: 20
 * </pre>
 *
 * <p>每步可用的键：{@code text}、{@code reasoning}、{@code tool_calls}（{@code id}、{@code name}、{@code arguments}：映射或 JSON 文本）、
 * {@code chunk_size}、{@code delay_ms}、{@code usage}（{@code prompt_tokens}、{@code completion_tokens}）、{@code finish_reason}、
 * {@code error}（{@code status}、{@code message}、{@code retry_after}）、{@code fail_after_chunks}。未知的键让加载失败，避免拼错的键被静默忽略。
 */
public final class ScriptLoader {

    /** 内置脚本在类路径中的目录。 */
    static final String BUILTIN_DIRECTORY = "cn/refinex/meridian/testsupport/model/scripts/";

    /** 内置脚本的名称，与 {@link #BUILTIN_DIRECTORY} 下的文件名一一对应（模块 {@code CLAUDE.md} 中的清单）。 */
    public static final List<String> BUILTIN = List.of(
            "chat",
            "tool-once",
            "approval",
            "custom-tool",
            "long-stream",
            "subagent",
            "error-before-first-chunk",
            "error-after-first-chunk");

    private static final List<String> STEP_KEYS = List.of(
            "text",
            "reasoning",
            "tool_calls",
            "chunk_size",
            "delay_ms",
            "usage",
            "finish_reason",
            "error",
            "fail_after_chunks");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ScriptLoader() {}

    /**
     * 读取全部内置脚本。
     *
     * @return 按名称索引的脚本
     */
    public static Map<String, ModelScript> builtin() {
        Map<String, ModelScript> scripts = new LinkedHashMap<>();
        for (String name : BUILTIN) {
            String resource = BUILTIN_DIRECTORY + name + ".yaml";
            try (InputStream in = ScriptLoader.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("缺少内置脚本：" + resource);
                }
                ModelScript script = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!script.name().equals(name)) {
                    throw new IllegalStateException("内置脚本的 name 与文件名不一致：" + resource);
                }
                scripts.put(name, script);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return scripts;
    }

    /**
     * 读取目录下的全部 {@code *.yaml} 脚本。
     *
     * @param directory 目录
     * @return 按名称索引的脚本
     */
    public static Map<String, ModelScript> directory(Path directory) {
        Map<String, ModelScript> scripts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                ModelScript script = parse(Files.readString(file));
                scripts.put(script.name(), script);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return scripts;
    }

    /**
     * 解析一个脚本。
     *
     * @param yaml YAML 文本
     * @return 脚本
     * @throws IllegalArgumentException 格式不对时
     */
    public static ModelScript parse(String yaml) {
        Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(document instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("脚本必须是 YAML 映射");
        }
        Object name = root.get("name");
        if (!(name instanceof String scriptName) || scriptName.isBlank()) {
            throw new IllegalArgumentException("脚本缺少 name");
        }
        if (!(root.get("steps") instanceof List<?> rawSteps)) {
            throw new IllegalArgumentException("脚本缺少 steps：" + scriptName);
        }
        List<Step> steps = new ArrayList<>();
        for (Object raw : rawSteps) {
            if (!(raw instanceof Map<?, ?> step)) {
                throw new IllegalArgumentException("步骤必须是映射：" + scriptName);
            }
            steps.add(step(scriptName, step));
        }
        return new ModelScript(scriptName, (String) root.get("description"), steps);
    }

    private static Step step(String script, Map<?, ?> step) {
        for (Object key : step.keySet()) {
            if (!STEP_KEYS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("脚本 " + script + " 的步骤有未知的键：" + key);
            }
        }
        List<ToolCall> calls = new ArrayList<>();
        if (step.get("tool_calls") instanceof List<?> list) {
            for (Object item : list) {
                Map<?, ?> call = (Map<?, ?>) item;
                Object arguments = call.get("arguments");
                String json = arguments == null
                        ? "{}"
                        : arguments instanceof String text ? text : JSON.writeValueAsString(arguments);
                calls.add(new ToolCall((String) call.get("id"), (String) call.get("name"), json));
            }
        }
        Usage usage = null;
        if (step.get("usage") instanceof Map<?, ?> map) {
            usage = new Usage(integer(map.get("prompt_tokens"), 0), integer(map.get("completion_tokens"), 0));
        }
        HttpError error = null;
        if (step.get("error") instanceof Map<?, ?> map) {
            Object retryAfter = map.get("retry_after");
            error = new HttpError(
                    integer(map.get("status"), 500),
                    (String) map.get("message"),
                    retryAfter == null ? null : integer(retryAfter, 0));
        }
        Object failAfter = step.get("fail_after_chunks");
        return new Step(
                text(step.get("text")),
                text(step.get("reasoning")),
                calls,
                integer(step.get("chunk_size"), Step.DEFAULT_CHUNK_SIZE),
                integer(step.get("delay_ms"), 0),
                usage,
                (String) step.get("finish_reason"),
                error,
                failAfter == null ? null : integer(failAfter, 0));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
