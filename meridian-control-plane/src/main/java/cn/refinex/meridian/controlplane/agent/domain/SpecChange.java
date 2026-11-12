package cn.refinex.meridian.controlplane.agent.domain;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * 两个版本规格之间的一处差异。
 *
 * @param path JSON 指针（RFC 6901），例如 {@code /subagents/0/tools}
 * @param kind 差异种类
 * @param before 基准版本中的取值；新增时为空
 * @param after 目标版本中的取值；删除时为空
 */
public record SpecChange(String path, Kind kind, JsonNode before, JsonNode after) {

    /**
     * 创建差异。
     *
     * @throws NullPointerException 缺少路径或种类时
     */
    public SpecChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }

    /** 差异种类。 */
    public enum Kind {
        /** 目标版本新增。 */
        ADDED("added"),
        /** 目标版本删除。 */
        REMOVED("removed"),
        /** 取值不同。 */
        CHANGED("changed");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        /**
         * 返回接口中的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }
}
