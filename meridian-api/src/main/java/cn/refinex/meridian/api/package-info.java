/**
 * 对外 API 的 OpenAPI 规范与由它生成的 DTO，是 control-plane、runtime、knowledge 共用的契约。
 *
 * <p>边界：不包含服务端接口与处理器：各应用在自己的模块中从同一份规范生成服务端接口（runtime 为 reactive，其余为 servlet）。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-common}。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.api;
