package cn.refinex.meridian.engine.spi.spec;

import java.util.List;
import java.util.Objects;

/**
 * 已解析的环境配置：执行档位、沙箱规格与出网白名单，见架构设计「核心概念」「环境档位」「沙箱出网」。
 *
 * <p>环境有修订号，修改即递增；内核按 {@code (agentVersionId, environmentId, revision)} 缓存智能体实例，
 * 所以环境修改后新会话自然使用新实例。
 *
 * @param environmentId 环境 ID（{@code env_…}）；必填
 * @param revision 修订号，从 1 开始
 * @param tier 执行档位；必填
 * @param size 沙箱规格；{@code tier} 为 {@link Tier#SANDBOX} 时必填，为 {@link Tier#NONE} 时必须为空
 * @param egressAllowlist 沙箱出网的域名白名单，例如 {@code pypi.org}、{@code *.example.com}；可为空列表，
 *     表示无出网（架构原则第 5 条"沙箱默认无出网"）。{@code none} 档没有沙箱，不使用
 */
public record EnvironmentSpec(
        String environmentId, long revision, Tier tier, SandboxSize size, List<String> egressAllowlist) {

    /**
     * 创建环境配置。
     *
     * @throws NullPointerException 缺少环境 ID 或档位时
     * @throws IllegalArgumentException 修订号小于 1，或规格与档位不匹配时
     */
    public EnvironmentSpec {
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(tier, "tier");
        if (revision < 1) {
            throw new IllegalArgumentException("环境修订号必须从 1 开始：" + revision);
        }
        if (tier == Tier.SANDBOX && size == null) {
            throw new IllegalArgumentException("sandbox 档必须指定规格：" + environmentId);
        }
        if (tier == Tier.NONE && size != null) {
            throw new IllegalArgumentException("none 档不能指定沙箱规格：" + environmentId);
        }
        egressAllowlist = egressAllowlist == null ? List.of() : List.copyOf(egressAllowlist);
    }

    /** 执行档位，见架构设计「环境档位」。 */
    public enum Tier {
        /** 平台虚拟文件系统，不提供 {@code bash}。 */
        NONE("none"),
        /** 会话沙箱内的文件系统与 {@code bash}；受保护路径和大结果路由到平台存储。 */
        SANDBOX("sandbox");

        private final String value;

        Tier(String value) {
            this.value = value;
        }

        /**
         * 返回配置与 API 中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }

    /**
     * 沙箱资源规格，各对应一套由 Helm 预部署的沙箱模板与预热池，见架构设计「沙箱 Pod 与镜像」。
     */
    public enum SandboxSize {
        /** 1 vCPU、2 GiB 内存、5 GiB 磁盘、256 进程。 */
        SMALL("small"),
        /** 2 vCPU、4 GiB 内存、10 GiB 磁盘、512 进程。 */
        MEDIUM("medium"),
        /** 4 vCPU、8 GiB 内存、20 GiB 磁盘、1024 进程。 */
        LARGE("large");

        private final String value;

        SandboxSize(String value) {
            this.value = value;
        }

        /**
         * 返回配置与 API 中使用的取值，也是沙箱模板与预热池名的后缀。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }
}
