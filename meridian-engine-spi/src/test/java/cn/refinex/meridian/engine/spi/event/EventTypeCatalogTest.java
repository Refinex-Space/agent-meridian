package cn.refinex.meridian.engine.spi.event;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 事件类型清单与架构设计「事件」一节逐一对应（T03-A02）：文档表格、{@link EventType} 与 {@link EngineEvent}
 * 封闭层级三者必须完全一致。测试直接读取 {@code docs/architecture/architecture.md}，文档或代码任何一边变化都会失败。
 */
class EventTypeCatalogTest {

    private static final Path ARCHITECTURE = Path.of("..", "docs", "architecture", "architecture.md");

    /** 表格中的事件类型写法：{@code user.message}、{@code event_delta}；排除 {@code delivery: queue | steer} 这类说明。 */
    private static final Pattern TYPE_TOKEN = Pattern.compile("`([a-z_]+(?:\\.[a-z_]+)?)`");

    @Test
    void documentedEventTypesMatchEnumOneToOne() throws IOException {
        Map<String, EventType.Domain> documented = documentedTypes();

        assertThat(documented).as("文档「事件」一节的事件类型清单不能为空").hasSizeGreaterThanOrEqualTo(20);
        Map<String, EventType.Domain> declared = Arrays.stream(EventType.values())
                .collect(Collectors.toMap(EventType::value, EventType::domain, (a, b) -> a, LinkedHashMap::new));
        assertThat(declared).as("EventType 与文档清单（类型与所属的域）逐一对应").isEqualTo(documented);
    }

    @Test
    void sealedHierarchyLeavesAreExactlyTheRegisteredEventClasses() {
        Set<Class<?>> leaves = leavesOf(EngineEvent.class);

        assertThat(leaves)
                .allSatisfy(leaf -> assertThat(leaf.isRecord())
                        .as("%s 是 record", leaf.getName())
                        .isTrue());
        Set<Class<?>> registered =
                Arrays.stream(EventType.values()).map(EventType::eventClass).collect(Collectors.toSet());
        assertThat(registered).as("每种事件类型的记录类互不相同").hasSize(EventType.values().length);
        assertThat(leaves).as("封闭层级的叶子与 EventType 登记的记录类一一对应").isEqualTo(registered);
    }

    @Test
    void eventsReportTheirRegisteredType() {
        assertThat(new UserEvent.Message(List.of(new MessageContent.Text("你好")), UserEvent.Delivery.STEER).type())
                .isEqualTo(EventType.USER_MESSAGE);
        assertThat(new SessionEvent.StatusIdle(StopReason.END_TURN).type()).isEqualTo(EventType.SESSION_STATUS_IDLE);
        assertThat(new StreamEvent.Ping().type()).isEqualTo(EventType.PING);
        assertThat(EventType.fromValue("agent.tool_result")).isEqualTo(EventType.AGENT_TOOL_RESULT);
    }

    @Test
    void onlyStreamEventsAreNotPersisted() {
        Set<EventType> transientTypes =
                Arrays.stream(EventType.values()).filter(t -> !t.isPersisted()).collect(Collectors.toSet());

        assertThat(transientTypes)
                .containsExactlyInAnyOrder(EventType.EVENT_START, EventType.EVENT_DELTA, EventType.PING);
    }

    /** 读取「事件」一节"事件类型清单："之后的表格：第一列是域，第二列是该域的事件类型。 */
    private static Map<String, EventType.Domain> documentedTypes() throws IOException {
        List<String> lines = Files.readAllLines(ARCHITECTURE, StandardCharsets.UTF_8);
        int start = lines.indexOf("事件类型清单：");
        assertThat(start).as("架构设计中有「事件类型清单：」一行").isNotNegative();
        Map<String, EventType.Domain> types = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : lines.subList(start + 1, lines.size())) {
            if (!line.startsWith("|")) {
                if (inTable) {
                    break;
                }
                continue;
            }
            inTable = true;
            String[] cells = line.split("\\|", -1);
            String domainCell = cells[1].strip();
            if (domainCell.equals("域") || domainCell.startsWith("---")) {
                continue;
            }
            EventType.Domain domain = domainOf(domainCell);
            // 第二列含转义的竖线（delivery: queue \| steer），按列切分会被截断，所以从整行提取
            Matcher matcher = TYPE_TOKEN.matcher(line.substring(line.indexOf('|', 1)));
            while (matcher.find()) {
                types.put(matcher.group(1), domain);
            }
        }
        return types;
    }

    private static EventType.Domain domainOf(String cell) {
        if (cell.startsWith("user")) {
            return EventType.Domain.USER;
        }
        if (cell.startsWith("agent")) {
            return EventType.Domain.AGENT;
        }
        if (cell.startsWith("session")) {
            return EventType.Domain.SESSION;
        }
        if (cell.startsWith("span")) {
            return EventType.Domain.SPAN;
        }
        if (cell.startsWith("只在流上")) {
            return EventType.Domain.STREAM;
        }
        throw new AssertionError("文档中出现了未知的事件域：" + cell);
    }

    private static Set<Class<?>> leavesOf(Class<?> root) {
        Set<Class<?>> leaves = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Class<?> current = pending.pop();
            Class<?>[] permitted = current.getPermittedSubclasses();
            if (permitted == null) {
                leaves.add(current);
            } else {
                pending.addAll(Arrays.asList(permitted));
            }
        }
        return leaves;
    }
}
