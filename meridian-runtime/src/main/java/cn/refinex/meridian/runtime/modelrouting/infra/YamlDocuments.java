package cn.refinex.meridian.runtime.modelrouting.infra;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 读取开发配置目录：每个 {@code *.yaml}、{@code *.yml} 文件是一份顶层为映射的文档，只解析 YAML 的基本类型
 * （{@link SafeConstructor}）。目录不存在时没有文档。与 {@code config.FileAgentConfigProvider} 的约定相同。
 */
final class YamlDocuments {

    private YamlDocuments() {}

    /**
     * 读出目录下的全部文档，按文件名排序。
     *
     * @param directory 目录
     * @return 文档
     */
    static List<Map<String, Object>> load(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(YamlDocuments::isYaml)
                    .sorted()
                    .map(YamlDocuments::loadFile)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取开发配置目录失败：" + directory, e);
        }
    }

    /**
     * 读取整数字段。
     *
     * @param document 文档
     * @param field 字段名
     * @param defaultValue 缺省值
     * @return 字段值
     * @throws IllegalStateException 字段存在但不是数字时
     */
    static long number(Map<?, ?> document, String field, long defaultValue) {
        Object value = document.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("开发配置字段 " + field + " 必须是数字：" + value);
    }

    /**
     * 读取必填的字符串字段。
     *
     * @param document 文档
     * @param field 字段名
     * @return 字段值
     * @throws IllegalStateException 字段缺失时
     */
    static String required(Map<?, ?> document, String field) {
        Object value = document.get(field);
        if (value == null) {
            throw new IllegalStateException("开发配置缺少字段 " + field + "：" + document.get("kind"));
        }
        return value.toString();
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }

    private static Map<String, Object> loadFile(Path file) {
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
