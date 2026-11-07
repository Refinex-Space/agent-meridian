/**
 * 带前缀的 ULID：{@link cn.refinex.meridian.common.id.IdPrefix} 登记架构设计「ID 规则」中的全部前缀，
 * {@link cn.refinex.meridian.common.id.Ids} 负责生成、解析与校验。
 *
 * <p>所有对外 ID 都经这里生成，业务代码不自行拼接 ID。不依赖其他平台包以外的任何框架。
 */
package cn.refinex.meridian.common.id;
