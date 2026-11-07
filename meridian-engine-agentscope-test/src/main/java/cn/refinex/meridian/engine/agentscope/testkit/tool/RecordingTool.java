package cn.refinex.meridian.engine.agentscope.testkit.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * 记录调用的测试工具，继承 {@link ToolBase}（与平台工具相同的基类）。默认返回入参中 {@code text} 字段的文本。
 *
 * <p>线程安全：并行工具调用会并发调用它，调用记录用写时复制列表保存。
 */
public final class RecordingTool extends ToolBase {

    /** 只有一个字符串参数 {@code text} 的入参 schema。 */
    public static final Map<String, Object> TEXT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("text", Map.of("type", "string", "description", "要回显的文本")),
            "required", List.of("text"));

    private final Function<Map<String, Object>, String> responder;
    private final List<ToolCallParam> calls = new CopyOnWriteArrayList<>();

    private RecordingTool(String name, Function<Map<String, Object>, String> responder) {
        super(ToolBase.builder()
                .name(name)
                .description("测试工具 " + name)
                .inputSchema(TEXT_SCHEMA)
                .readOnly(true)
                .concurrencySafe(true));
        this.responder = responder;
    }

    /**
     * 回显入参 {@code text} 的工具。
     *
     * @param name 工具名
     * @return 工具
     */
    public static RecordingTool echo(String name) {
        return new RecordingTool(name, input -> name + ":" + input.get("text"));
    }

    /**
     * 按给定函数生成结果文本的工具。
     *
     * @param name 工具名
     * @param responder 由入参生成结果文本
     * @return 工具
     */
    public static RecordingTool responding(String name, Function<Map<String, Object>, String> responder) {
        return new RecordingTool(name, responder);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        calls.add(param);
        return Mono.fromSupplier(() -> ToolResultBlock.text(responder.apply(param.getInput())));
    }

    /**
     * 已发生的调用，按调用顺序。
     *
     * @return 调用参数
     */
    public List<ToolCallParam> calls() {
        return List.copyOf(calls);
    }
}
