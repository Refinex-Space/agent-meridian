package cn.refinex.meridian.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.output.ConsoleRender;

/**
 * 公开 API 的兼容性检查（ADR-0029，T07-A09）：与"上一个已提交版本"比较，出现破坏性变更时构建失败。破坏性变更只能出现在新的
 * 大版本（架构设计「API 设计」中的「约定」），新大版本是新文件，不改这一份。
 *
 * <p>基线的取法：工作区的规范与最近一次改动它的提交不同，基线是那次提交中的版本（本地改规范时与 HEAD 比较）；相同，基线是那次提交
 * 的父提交中的版本（CI 在提交之后仍检查最近一次改动）。git 不可用、浅克隆取不到父提交、规范第一次提交时没有基线，跳过并说明。
 *
 * <p>openapi-diff 2.1.7 检测不到 3.1 的字段类型变化（{@code type} 数组与单一类型都是），这里补一项检查：已有的组件 schema 属性与
 * 操作参数，类型集合或格式变化一律视为破坏性变更。
 */
class OpenApiCompatibilityTest {

    /** 规范相对仓库根目录的路径。 */
    static final String SPEC_PATH = "meridian-api/src/main/resources/openapi/meridian-v1.yaml";

    /** 类型名的输出顺序：{@code null} 排在最后，其余按字母。 */
    private static final Comparator<String> TYPE_ORDER =
            Comparator.comparing((String type) -> type.equals("null")).thenComparing(Comparator.naturalOrder());

    @Test
    void specIsBackwardCompatibleWithThePreviousCommittedVersion() throws Exception {
        Path repoRoot = repoRoot();
        String current = Files.readString(repoRoot.resolve(SPEC_PATH));
        Optional<Baseline> baseline = baseline(repoRoot, current);
        Assumptions.assumeTrue(baseline.isPresent(), "没有可比较的基线（git 不可用、浅克隆，或规范第一次提交），跳过兼容性检查");

        ChangedOpenApi diff = OpenApiCompare.fromContents(baseline.get().content(), current);
        List<String> typeChanges = typeChanges(parse(baseline.get().content()), parse(current));

        assertThat(diff.isIncompatible())
                .as("与 %s 相比有破坏性变更：%n%s", baseline.get().source(), render(diff))
                .isFalse();
        assertThat(typeChanges)
                .as(
                        "与 %s 相比，已有字段或参数的类型变化（openapi-diff 对 3.1 检测不到）",
                        baseline.get().source())
                .isEmpty();
    }

    @Test
    void typeCheckReportsChangedPropertyAndParameterTypes() {
        String before = """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  /a:
                    get:
                      parameters:
                        - {name: limit, in: query, schema: {type: integer}}
                      responses: {'200': {description: ok}}
                components:
                  schemas:
                    A:
                      type: object
                      properties:
                        x: {type: [string, 'null']}
                        y: {type: string, format: date-time}
                        z: {type: string}
                """;
        String after = before.replace("x: {type: [string, 'null']}", "x: {type: [integer, 'null']}")
                .replace("format: date-time", "format: date")
                .replace("schema: {type: integer}", "schema: {type: string}")
                .replace("z: {type: string}", "z: {type: string}\n        w: {type: boolean}");

        assertThat(typeChanges(parse(before), parse(after)))
                .containsExactly(
                        "A.x：[string, null] → [integer, null]",
                        "A.y：格式 date-time → date",
                        "GET /a 参数 limit：[integer] → [string]");
        assertThat(typeChanges(parse(before), parse(before))).isEmpty();
    }

    /** 已有组件属性与操作参数的类型、格式变化。新增的属性、参数与 schema 不算。 */
    @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models 的 getProperties() 返回原始类型的 Map<String, Schema>
    static List<String> typeChanges(OpenAPI before, OpenAPI after) {
        List<String> changes = new ArrayList<>();
        Map<String, Schema> oldSchemas = schemas(before);
        Map<String, Schema> newSchemas = schemas(after);
        oldSchemas.forEach((name, oldSchema) -> {
            Schema<?> newSchema = newSchemas.get(name);
            if (newSchema == null || oldSchema.getProperties() == null || newSchema.getProperties() == null) {
                return;
            }
            Map<String, Schema> newProperties = newSchema.getProperties();
            ((Map<String, Schema>) oldSchema.getProperties()).forEach((property, oldProperty) -> {
                Schema<?> newProperty = newProperties.get(property);
                if (newProperty != null) {
                    compare(name + "." + property, oldProperty, newProperty, changes);
                }
            });
        });
        Map<String, Parameter> oldParameters = parameters(before);
        Map<String, Parameter> newParameters = parameters(after);
        oldParameters.forEach((key, oldParameter) -> {
            Parameter newParameter = newParameters.get(key);
            if (newParameter != null && oldParameter.getSchema() != null && newParameter.getSchema() != null) {
                compare(key, oldParameter.getSchema(), newParameter.getSchema(), changes);
            }
        });
        return changes;
    }

    private static void compare(String where, Schema<?> before, Schema<?> after, List<String> changes) {
        if (before.get$ref() != null || after.get$ref() != null) {
            if (!Objects.equals(before.get$ref(), after.get$ref())) {
                changes.add(where + "：引用 " + before.get$ref() + " → " + after.get$ref());
            }
            return;
        }
        Set<String> oldTypes = types(before);
        Set<String> newTypes = types(after);
        if (!oldTypes.equals(newTypes)) {
            changes.add(where + "：" + oldTypes + " → " + newTypes);
        }
        if (!Objects.equals(before.getFormat(), after.getFormat())) {
            changes.add(where + "：格式 " + before.getFormat() + " → " + after.getFormat());
        }
    }

    private static Set<String> types(Schema<?> schema) {
        Set<String> types = new TreeSet<>(TYPE_ORDER);
        if (schema.getTypes() != null) {
            types.addAll(schema.getTypes());
        } else if (schema.getType() != null) {
            types.add(schema.getType());
        }
        return types;
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models 的 getSchemas() 返回原始类型的 Map<String, Schema>
    private static Map<String, Schema> schemas(OpenAPI api) {
        return api.getComponents() == null || api.getComponents().getSchemas() == null
                ? Map.of()
                : (Map<String, Schema>) (Map) api.getComponents().getSchemas();
    }

    private static Map<String, Parameter> parameters(OpenAPI api) {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        if (api.getPaths() == null) {
            return parameters;
        }
        api.getPaths()
                .forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                    List<Parameter> all = new ArrayList<>();
                    if (item.getParameters() != null) {
                        all.addAll(item.getParameters());
                    }
                    if (operation.getParameters() != null) {
                        all.addAll(operation.getParameters());
                    }
                    for (Parameter parameter : all) {
                        Parameter resolved = resolve(api, parameter);
                        parameters.put(method.name() + " " + path + " 参数 " + resolved.getName(), resolved);
                    }
                }));
        return parameters;
    }

    private static Parameter resolve(OpenAPI api, Parameter parameter) {
        if (parameter.get$ref() == null) {
            return parameter;
        }
        String name = parameter.get$ref().substring(parameter.get$ref().lastIndexOf('/') + 1);
        return api.getComponents().getParameters().get(name);
    }

    private static OpenAPI parse(String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        return new OpenAPIV3Parser().readContents(content, null, options).getOpenAPI();
    }

    private static String render(ChangedOpenApi diff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            new ConsoleRender().render(diff, writer);
        } catch (IOException e) {
            return "（无法输出变更说明：" + e.getMessage() + "）";
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** 基线的取法见类注释。 */
    private static Optional<Baseline> baseline(Path repoRoot, String current) {
        Optional<String> lastCommit =
                git(repoRoot, "log", "-1", "--format=%H", "--", SPEC_PATH).filter(hash -> !hash.isBlank());
        if (lastCommit.isEmpty()) {
            return Optional.empty();
        }
        String commit = lastCommit.get().strip();
        Optional<String> committed = git(repoRoot, "show", commit + ":" + SPEC_PATH);
        if (committed.isPresent() && !committed.get().equals(current)) {
            return Optional.of(new Baseline(commit + "（最近一次提交的版本）", committed.get()));
        }
        return git(repoRoot, "show", commit + "^:" + SPEC_PATH)
                .map(parent -> new Baseline(commit + "^（最近一次改动之前的版本）", parent));
    }

    private static Optional<String> git(Path repoRoot, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Path repoRoot() {
        // Surefire 的工作目录是模块目录
        Path module = Path.of("").toAbsolutePath();
        return module.getFileName().toString().equals("meridian-api") ? module.getParent() : module;
    }

    private record Baseline(String source, String content) {}
}
