/**
 * 对外 API 的 OpenAPI 规范与由它生成的 DTO，是 control-plane、runtime、knowledge 共用的契约。
 *
 * <p>DTO 由 openapi-generator 在构建时生成到 {@code cn.refinex.meridian.api.model}（不进源码树），规范在
 * {@code openapi/meridian-v1.yaml}；写作约束与生成选项见 ADR-0029 与本模块的 CLAUDE.md。
 *
 * <p>边界：不包含服务端接口与处理器：各应用在自己的模块中从同一份规范生成服务端接口（runtime 为 reactive，其余为 servlet）。
 *
 * <p>允许的依赖：平台模块只允许依赖 {@code meridian-common}（架构设计「模块依赖规则」）；第三方依赖只有生成代码用到的注解库。
 */
package cn.refinex.meridian.api;
