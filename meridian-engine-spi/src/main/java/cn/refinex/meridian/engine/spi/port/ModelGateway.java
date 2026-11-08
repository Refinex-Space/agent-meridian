package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.event.TokenUsage;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 模型调用的平台侧服务：解析供应商账号与凭据、限流、预算预扣与结算、用量记录，供 {@code RoutedModel} 在每次
 * {@code doStream} 中依次调用，由 runtime 的 {@code modelrouting} 实现，见架构设计「端口」「模型调用」「计量与预算」。
 *
 * <p>租户、项目、API Key、会话与预算句柄从 Reactor Context 中的 {@code PlatformContext} 取，压缩器内部的模型调用也在
 * 同一条订阅链上。预算与限流异常不重试、不切换备用模型。
 */
public interface ModelGateway {

    /**
     * 读取模型目录中的静态信息：上下文窗口与默认最大输出。不解析凭据，不需要 {@code PlatformContext}，
     * 供装配时设置 {@code setContextWindowSize}（架构设计「上下文管理」）。
     *
     * @param modelId 模型 ID（{@code mdl_…}）
     * @return 模型信息；模型不存在时以 {@code NotFoundException} 失败
     */
    Mono<ModelDescriptor> describe(String modelId);

    /**
     * 解析模型的供应商账号、端点与凭据。结果可以按"供应商账号加模型"缓存。
     *
     * @param modelId 模型 ID（{@code mdl_…}）
     * @return 路由信息；模型不存在或未授权给该项目时以 {@code NotFoundException} 失败
     */
    Mono<ModelRoute> resolve(String modelId);

    /**
     * 按"供应商账号加模型"的 RPM 与 TPM 用令牌桶等待，最长 30 秒。
     *
     * @param route 路由信息
     * @param estimatedTokens 估算的本次 token 数（输入加最大输出），不小于 0
     * @return 拿到配额后完成；等待超时以 {@code RateLimitTimeoutException} 失败（可重试）
     */
    Mono<Void> awaitRateLimit(ModelRoute route, int estimatedTokens);

    /**
     * 只检查、不占用：四级预算是否容得下本次调用，限流是否能在等待上限内拿到配额。压缩前由
     * {@code CompactionGuardMiddleware} 调用，因为压缩器会吞掉模型异常（架构设计「平台中间件」）。
     *
     * @param route 路由信息
     * @param estimatedTokens 估算的本次 token 数，不小于 0
     * @return 检查通过后完成；预算不足以 {@code BudgetExceededException} 失败，限流等待会超过上限时以
     *     {@code RateLimitTimeoutException} 失败
     */
    Mono<Void> precheck(ModelRoute route, int estimatedTokens);

    /**
     * 按"估算输入 token 加 {@code max_tokens}"预扣预算。
     *
     * @param route 路由信息
     * @param estimatedTokens 估算的本次 token 数，不小于 0
     * @return 预扣凭据；预算不足时以 {@code BudgetExceededException} 失败，本轮以 {@code budget_exceeded} 结束
     */
    Mono<BudgetReservation> reserve(ModelRoute route, int estimatedTokens);

    /**
     * 在流结束（含出错、取消）时按实际用量结算预扣、退回未使用的部分，并写入用量记录（经 outbox 投递到 control-plane）。
     *
     * @param reservation 预扣凭据
     * @param record 本次调用的用量与计量信息
     * @return 结算并记录后完成
     */
    Mono<Void> settle(BudgetReservation reservation, ModelCallRecord record);

    /**
     * 调用在产生用量之前失败（首包之前出错、被取消）时，释放预扣，不写用量记录。
     *
     * @param reservation 预扣凭据
     * @return 释放后完成
     */
    Mono<Void> release(BudgetReservation reservation);

    /**
     * 模型目录中的静态信息。
     *
     * @param modelId 模型 ID；必填
     * @param contextWindow 上下文窗口，单位 token，大于 0
     * @param maxOutputTokens 请求没有指定时使用的最大输出 token 数，大于 0；预扣按它计算
     */
    record ModelDescriptor(String modelId, int contextWindow, int maxOutputTokens) {

        /**
         * 创建模型信息。
         *
         * @throws NullPointerException 缺少模型 ID 时
         * @throws IllegalArgumentException 窗口或最大输出不大于 0 时
         */
        public ModelDescriptor {
            Objects.requireNonNull(modelId, "modelId");
            if (contextWindow < 1 || maxOutputTokens < 1) {
                throw new IllegalArgumentException("contextWindow 与 maxOutputTokens 必须大于 0");
            }
        }
    }

    /**
     * 模型的路由信息。
     *
     * @param modelId 模型 ID；必填
     * @param providerAccountId 供应商账号 ID（{@code prv_…}）；必填，限流与底层客户端缓存的键
     * @param provider 供应商类型，决定用哪个 AgentScope 模型类构造底层客户端；必填
     * @param baseUrl 供应商端点；可为空，表示用该供应商的默认端点
     * @param upstreamModel 供应商侧的模型名，例如 {@code qwen-plus}；必填
     * @param apiKey 凭据明文；可为空，表示该供应商不需要凭据（例如本地 Ollama）
     * @param contextWindow 模型上下文窗口，单位 token，大于 0；压缩阈值据此计算
     * @param maxOutputTokens 请求没有指定时使用的最大输出 token 数，大于 0
     */
    record ModelRoute(
            String modelId,
            String providerAccountId,
            Provider provider,
            String baseUrl,
            String upstreamModel,
            SecretValue apiKey,
            int contextWindow,
            int maxOutputTokens) {

        /**
         * 创建路由信息。
         *
         * @throws NullPointerException 缺少必填字段时
         * @throws IllegalArgumentException 上下文窗口或最大输出不大于 0 时
         */
        public ModelRoute {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(providerAccountId, "providerAccountId");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(upstreamModel, "upstreamModel");
            if (contextWindow < 1) {
                throw new IllegalArgumentException("contextWindow 必须大于 0：" + contextWindow);
            }
            if (maxOutputTokens < 1) {
                throw new IllegalArgumentException("maxOutputTokens 必须大于 0：" + maxOutputTokens);
            }
        }
    }

    /** 供应商类型，与架构设计「模型调用」列出的 AgentScope 模型类对应。 */
    enum Provider {
        /** OpenAI 兼容接口（含 DashScope 兼容模式、私有化部署的兼容服务）。 */
        OPENAI_COMPATIBLE,
        /** Anthropic。 */
        ANTHROPIC,
        /** Gemini。 */
        GEMINI,
        /** DashScope 原生接口。 */
        DASHSCOPE,
        /** Ollama。 */
        OLLAMA;

        /**
         * 配置与消息中的取值。
         *
         * @return 小写的常量名，例如 {@code openai_compatible}
         */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 一次预算预扣的凭据。
     *
     * @param reservationId 预扣 ID，结算与释放时使用；必填
     * @param reservedTokens 预扣的 token 数，不小于 0
     */
    record BudgetReservation(String reservationId, long reservedTokens) {

        /**
         * 创建预扣凭据。
         *
         * @throws NullPointerException 缺少预扣 ID 时
         * @throws IllegalArgumentException 预扣数为负时
         */
        public BudgetReservation {
            Objects.requireNonNull(reservationId, "reservationId");
            if (reservedTokens < 0) {
                throw new IllegalArgumentException("reservedTokens 不能为负：" + reservedTokens);
            }
        }
    }

    /** 模型调用的用途，写进用量记录，区分智能体推理与压缩器的内部调用。 */
    enum CallPurpose {
        /** 智能体推理（含 OpenAI 兼容直通接口的调用）。 */
        REASONING,
        /** 压缩器生成摘要的内部调用。 */
        COMPACTION;

        /**
         * 消息中的取值。
         *
         * @return 小写的常量名
         */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 用量的来源。 */
    enum UsageSource {
        /** 供应商在响应中报告的用量。 */
        PROVIDER,
        /** 供应商没有报告（流被取消、首包之后出错、响应不带用量）时按字符数估算的用量，宁可多记。 */
        ESTIMATED;

        /**
         * 消息中的取值。
         *
         * @return 小写的常量名
         */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 一次模型调用的计量信息，结算与用量记录使用。
     *
     * @param requestId 本次调用的 ID，用量记录据此去重；必填
     * @param route 实际调用的模型路由（降级后为备用模型）；必填
     * @param agentVersionId 发起调用的智能体版本 ID；可为空，表示不经智能体（直通接口）
     * @param purpose 调用用途；必填
     * @param usage 用量；必填
     * @param source 用量来源；必填
     * @param firstTokenLatency 从发出请求到收到第一个带内容的增量的时间；可为空，表示没有收到内容
     * @param fallback 是否为降级到备用模型后的调用
     */
    record ModelCallRecord(
            String requestId,
            ModelRoute route,
            String agentVersionId,
            CallPurpose purpose,
            TokenUsage usage,
            UsageSource source,
            Duration firstTokenLatency,
            boolean fallback) {

        /**
         * 创建计量信息。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public ModelCallRecord {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(source, "source");
        }
    }
}
