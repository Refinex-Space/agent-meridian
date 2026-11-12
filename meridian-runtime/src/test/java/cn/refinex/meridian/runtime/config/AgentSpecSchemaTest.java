package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.KnowledgeSpec;
import cn.refinex.meridian.engine.spi.spec.McpServerSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 智能体规格的 JSON Schema（meridian-api 的 {@code schema/agent-spec.schema.json}）与 engine-spi 的 {@link ResolvedAgentSpec} 一一对应
 * （T15b-A02，ADR-0047）：
 *
 * <ul>
 *   <li>从 Schema 生成一份示例（对象取全部字段，叶子取 {@code examples} 的第一个），它通过 Schema 校验，按 runtime 的 JSON 约定
 *       反序列化为 {@link ResolvedAgentSpec} 再序列化，结果与示例相同；
 *   <li>每个记录的组件与 Schema 中对应对象的字段一一对应（camelCase ↔ snake_case）；
 *   <li>平台工具、副作用、权限模式、结果、传输的取值与 Java 的枚举与常量相同。
 * </ul>
 *
 * <p>control-plane 发布时按同一份 Schema 校验并写入快照，runtime 收到的规格因此一定能构造出来。
 */
class AgentSpecSchemaTest {

    private static JsonNode schema;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in =
                AgentSpecSchemaTest.class.getClassLoader().getResourceAsStream("schema/agent-spec.schema.json")) {
            assertThat(in).as("meridian-api 的 schema/agent-spec.schema.json").isNotNull();
            schema = JsonMappers.lenient().readTree(in);
        }
    }

    @Test
    void anExampleGeneratedFromTheSchemaRoundTripsThroughResolvedAgentSpec() {
        JsonNode example = generate(schema);
        String json = JsonMappers.lenient().writeValueAsString(example);

        var errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JsonMappers.lenient().writeValueAsString(schema), InputFormat.JSON)
                .validate(json, InputFormat.JSON);
        assertThat(errors).as("生成的示例符合 Schema").isEmpty();

        ResolvedAgentSpec spec =
                EngineJson.convert(JsonMappers.lenient().readValue(json, Map.class), ResolvedAgentSpec.class);
        JsonNode back = JsonMappers.lenient().readTree(EngineJson.write(spec));

        assertThat(normalize(back)).isEqualTo(normalize(example));
        assertThat(spec.subagents()).hasSize(1);
        assertThat(spec.mcpServers().getFirst().sideEffectOf("lookup_order")).isEqualTo(SideEffect.NONE);
    }

    @Test
    void everyRecordComponentHasASchemaPropertyAndViceVersa() {
        assertFields(ResolvedAgentSpec.class, schema);
        assertFields(ModelSpec.class, def("model"));
        assertFields(McpServerSpec.class, def("mcp_server"));
        assertFields(SkillRef.class, def("skill_ref"));
        assertFields(SubagentSpec.class, def("subagent"));
        assertFields(PermissionPolicy.class, def("permission_policy"));
        assertFields(PermissionRule.class, def("permission_rule"));
        assertFields(CompactionSpec.class, def("compaction"));
        assertFields(KnowledgeSpec.class, def("knowledge"));
    }

    @Test
    void enumeratedValuesMatchThePlatformConstants() {
        assertThat(strings(def("platform_tool").get("enum"))).isEqualTo(new TreeSet<>(PlatformToolNames.ALL));
        assertThat(strings(def("side_effect").get("enum")))
                .isEqualTo(Arrays.stream(SideEffect.values())
                        .map(SideEffect::value)
                        .collect(Collectors.toCollection(TreeSet::new)));
        assertThat(strings(def("permission_policy").at("/properties/mode/enum")))
                .isEqualTo(Arrays.stream(PermissionMode.values())
                        .map(PermissionMode::value)
                        .collect(Collectors.toCollection(TreeSet::new)));
        assertThat(strings(def("permission_rule").at("/properties/behavior/enum")))
                .isEqualTo(Arrays.stream(PermissionBehavior.values())
                        .map(PermissionBehavior::value)
                        .collect(Collectors.toCollection(TreeSet::new)));
        assertThat(strings(def("mcp_server").at("/properties/transport/enum")))
                .isEqualTo(Arrays.stream(McpServerSpec.McpTransport.values())
                        .map(t -> t.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toCollection(TreeSet::new)));
        assertThat(def("agent_name").get("pattern").asString()).isEqualTo(SubagentSpec.NAME.pattern());
        assertThat(schema.at("/properties/max_iters/maximum").asInt()).isEqualTo(ResolvedAgentSpec.MAX_ITERS_LIMIT);
        assertThat(def("knowledge").at("/properties/prefetch_top_k/maximum").asInt())
                .isEqualTo(KnowledgeSpec.MAX_PREFETCH_TOP_K);
    }

    /** 按 Schema 生成示例：有 {@code examples} 的取第一个，{@code $ref} 解析，对象取全部字段，数组一个元素。 */
    private static JsonNode generate(JsonNode node) {
        if (node.has("examples")) {
            return node.get("examples").get(0);
        }
        if (node.has("$ref")) {
            return generate(resolve(node.get("$ref").asString()));
        }
        if ("object".equals(node.path("type").asString(null)) && node.has("properties")) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            for (String name : node.get("properties").propertyNames()) {
                object.set(name, generate(node.get("properties").get(name)));
            }
            return object;
        }
        if ("array".equals(node.path("type").asString(null))) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            array.add(generate(node.get("items")));
            return array;
        }
        throw new AssertionError("Schema 的叶子缺少 examples：" + node);
    }

    private static JsonNode resolve(String ref) {
        assertThat(ref).startsWith("#/$defs/");
        return def(ref.substring("#/$defs/".length()));
    }

    private static JsonNode def(String name) {
        JsonNode def = schema.get("$defs").get(name);
        assertThat(def).as("$defs/" + name).isNotNull();
        return def;
    }

    private static void assertFields(Class<? extends Record> type, JsonNode object) {
        Set<String> components = Arrays.stream(type.getRecordComponents())
                .map(c -> snake(c.getName()))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(new TreeSet<>(object.get("properties").propertyNames()))
                .as(type.getSimpleName())
                .isEqualTo(components);
        assertThat(object.get("additionalProperties").asBoolean(true))
                .as(type.getSimpleName() + " 不允许多余字段")
                .isFalse();
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static TreeSet<String> strings(JsonNode array) {
        TreeSet<String> values = new TreeSet<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }

    /** 字符串数组排序：工具集等在 Java 中是集合，序列化顺序不定。 */
    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (String name : new TreeSet<>(node.propertyNames())) {
                copy.set(name, normalize(node.get(name)));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            var items = new ArrayList<JsonNode>();
            node.forEach(item -> items.add(normalize(item)));
            if (items.stream().allMatch(JsonNode::isString)) {
                items.sort((a, b) -> a.asString().compareTo(b.asString()));
            }
            items.forEach(copy::add);
            return copy;
        }
        return node;
    }
}
