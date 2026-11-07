package cn.refinex.meridian.runtime.dispatch.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * runtime 节点的身份，即 {@code rt.turns.lease_owner}，也用于中断通知频道 {@code node:{lease_owner}}
 * （架构设计「消息投递」）。
 *
 * <p>每个进程一个，进程重启后不同：默认取主机名加随机后缀，所以同一个 Pod 内容器重启后，新进程不会把上一个进程的
 * 租约当成自己的。
 *
 * @param value 节点 ID，只含字母、数字、点、下划线与短横线，最长 128 个字符
 */
public record NodeId(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /**
     * 创建节点 ID。
     *
     * @throws NullPointerException 值为空时
     * @throws IllegalArgumentException 含有不允许的字符或过长时
     */
    public NodeId {
        Objects.requireNonNull(value, "value");
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("节点 ID 只能包含字母、数字、点、下划线与短横线，最长 128 个字符：" + value);
        }
    }

    /**
     * 以主机名加 8 位随机后缀生成节点 ID。主机名中不允许的字符替换为短横线。
     *
     * @param hostname 主机名（Kubernetes 中为 Pod 名）；为空时用 {@code runtime}
     * @return 节点 ID
     */
    public static NodeId generate(String hostname) {
        String base = hostname == null || hostname.isBlank()
                ? "runtime"
                : hostname.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        return new NodeId(base + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public String toString() {
        return value;
    }
}
