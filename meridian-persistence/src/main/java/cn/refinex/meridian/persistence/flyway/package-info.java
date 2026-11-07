/**
 * 数据库迁移约定：每个应用的迁移在 {@code src/main/resources/db/migration/<schema>/}，只作用于自己的 schema；
 * 迁移用迁移角色执行，应用用只有 DML 权限的角色（编码规范「数据访问」）。
 *
 * <p>{@link cn.refinex.meridian.persistence.flyway.MigrationConventions} 把约定应用到 Spring Boot 的 Flyway 配置。
 * 本模块不包含任何迁移脚本，只在 {@code outbox}、{@code idempotency} 包旁提供建表片段，供各服务的迁移复制。
 */
package cn.refinex.meridian.persistence.flyway;
