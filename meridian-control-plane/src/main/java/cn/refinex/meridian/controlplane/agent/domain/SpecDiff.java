package cn.refinex.meridian.controlplane.agent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * 两个版本规格的差异（版本对比接口，ADR-0047）：对象按字段名递归比较；数组按位置比较，多出或缺少的元素是新增或删除；其他取值整体比较。
 * 结果按发现顺序（字段名字典序、数组下标升序），所以同样的输入总得到同样的输出。
 */
public final class SpecDiff {

    private SpecDiff() {}

    /**
     * 比较两个规格。
     *
     * @param base 基准版本的规格
     * @param target 目标版本的规格
     * @return 目标相对基准的差异；相同时为空列表
     */
    public static List<SpecChange> compare(JsonNode base, JsonNode target) {
        List<SpecChange> changes = new ArrayList<>();
        walk("", base, target, changes);
        return List.copyOf(changes);
    }

    private static void walk(String path, JsonNode before, JsonNode after, List<SpecChange> changes) {
        if (before.isObject() && after.isObject()) {
            TreeSet<String> names = new TreeSet<>(before.propertyNames());
            names.addAll(after.propertyNames());
            for (String name : names) {
                String child = path + "/" + escape(name);
                JsonNode b = before.get(name);
                JsonNode a = after.get(name);
                if (b == null) {
                    changes.add(new SpecChange(child, SpecChange.Kind.ADDED, null, a));
                } else if (a == null) {
                    changes.add(new SpecChange(child, SpecChange.Kind.REMOVED, b, null));
                } else {
                    walk(child, b, a, changes);
                }
            }
        } else if (before.isArray() && after.isArray()) {
            int common = Math.min(before.size(), after.size());
            for (int i = 0; i < common; i++) {
                walk(path + "/" + i, before.get(i), after.get(i), changes);
            }
            for (int i = common; i < after.size(); i++) {
                changes.add(new SpecChange(path + "/" + i, SpecChange.Kind.ADDED, null, after.get(i)));
            }
            for (int i = common; i < before.size(); i++) {
                changes.add(new SpecChange(path + "/" + i, SpecChange.Kind.REMOVED, before.get(i), null));
            }
        } else if (!before.equals(after)) {
            changes.add(new SpecChange(path.isEmpty() ? "/" : path, SpecChange.Kind.CHANGED, before, after));
        }
    }

    /** JSON 指针的转义：{@code ~} 写成 {@code ~0}，{@code /} 写成 {@code ~1}（RFC 6901）。 */
    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
