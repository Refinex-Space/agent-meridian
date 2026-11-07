/**
 * JDBC 公共能力：PostgreSQL 特有类型的读写（{@code jsonb}、{@code text[]}、{@code timestamptz} 与
 * {@code Instant}）、schema 名校验，以及本模块的 Spring Boot 自动配置。
 *
 * <p>数据源（HikariCP）、{@code JdbcClient}、事务管理器与 {@code TransactionTemplate} 沿用 Spring Boot 的自动配置；
 * 应用以只有 DML 权限的 {@code meridian_app} 连接，Flyway 以 {@code meridian_migrator} 执行迁移
 * （编码规范「数据访问」）。统一使用 {@code JdbcClient}，不引入 JPA 与 MyBatis（ADR-0020）。
 */
package cn.refinex.meridian.persistence.jdbc;
