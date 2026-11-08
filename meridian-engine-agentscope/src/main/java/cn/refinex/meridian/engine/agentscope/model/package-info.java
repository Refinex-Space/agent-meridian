/**
 * 平台的模型层：{@link cn.refinex.meridian.engine.agentscope.model.RoutedModel} 与生产用的模型工厂，见架构设计「模型调用」。
 *
 * <p>所有模型调用（含压缩器的内部调用）都经过 {@code RoutedModel}：经 {@code ModelGateway} 解析路由与凭据、限流、预扣、结算与计量，
 * 首包之前的供应商错误由它重试与降级。底层客户端是 AgentScope 对应供应商的模型类，由
 * {@link cn.refinex.meridian.engine.agentscope.model.ProviderClientFactory} 构造、按"供应商账号加模型"缓存，HTTP 走平台的
 * {@link cn.refinex.meridian.engine.agentscope.model.ProviderHttpTransport}。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-engine-spi}、AgentScope 2.0.4（含 {@code agentscope-extensions-model-openai}）、
 * Caffeine。
 */
package cn.refinex.meridian.engine.agentscope.model;
