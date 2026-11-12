package cn.refinex.meridian.controlplane.agent.app;

import cn.refinex.meridian.common.error.FieldViolation;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 智能体规格的 JSON Schema 校验（ADR-0047）：schema 是 meridian-api 中的 {@value #LOCATION}，与 engine-spi 的
 * {@code ResolvedAgentSpec} 一一对应（runtime 的 {@code AgentSpecSchemaTest} 守住），控制台用同一份文件在本地校验表单。
 *
 * <p>校验失败转成字段错误：字段名是 {@code spec.} 加点号连接的 JSON 路径（{@code spec.subagents.0.tools}），缺少或多出的字段带上字段名。
 *
 * <p>线程安全：编译后的 schema 不可变，可以并发校验。
 */
@Component
public class AgentSpecSchema {

    /** schema 在类路径中的位置（meridian-api）。 */
    public static final String LOCATION = "schema/agent-spec.schema.json";

    /** 字段名的前缀：规格在请求体中的字段。 */
    static final String FIELD_PREFIX = "spec";

    private final Schema schema;

    /** 编译 schema。 */
    public AgentSpecSchema() {
        try (InputStream in = AgentSpecSchema.class.getClassLoader().getResourceAsStream(LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("类路径中没有智能体规格的 JSON Schema：" + LOCATION);
            }
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(in, InputFormat.JSON);
        } catch (IOException e) {
            throw new UncheckedIOException("读取智能体规格的 JSON Schema 失败", e);
        }
    }

    /**
     * 校验一份规格。
     *
     * @param specJson 规格 JSON
     * @return 字段错误，按发现顺序去重；通过时为空列表
     */
    public List<FieldViolation> validate(String specJson) {
        Set<FieldViolation> violations = new LinkedHashSet<>();
        for (Error error : schema.validate(specJson, InputFormat.JSON)) {
            violations.add(new FieldViolation(field(error), error.getMessage()));
        }
        return List.copyOf(violations);
    }

    private static String field(Error error) {
        StringBuilder field = new StringBuilder(FIELD_PREFIX);
        NodePath location = error.getInstanceLocation();
        for (int i = 0; i < location.getNameCount(); i++) {
            field.append('.').append(location.getElement(i));
        }
        String keyword = error.getKeyword();
        if (error.getProperty() != null && ("required".equals(keyword) || "additionalProperties".equals(keyword))) {
            field.append('.').append(error.getProperty());
        }
        return field.toString();
    }
}
