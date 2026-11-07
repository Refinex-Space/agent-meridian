package cn.refinex.meridian.common.error;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 平台错误码：码值、HTTP 状态与默认标题，在 HTTP 边界映射为 problem+json（RFC 9457）的 {@code code}、
 * {@code status}、{@code title} 与 {@code type}，见架构设计「API 设计」中的「约定」。
 *
 * <p>注册约定：
 * <ul>
 *   <li>每个业务领域用一个枚举实现本接口，例如 runtime 的 {@code SessionErrorCode}；码值的第一段是领域名，
 *       一个领域只由一个枚举拥有，所以码值天然不冲突。
 *   <li>码值形如 {@code <领域>.<原因>}，全小写 snake_case，例如 {@code session.not_found}（编码规范「命名」）。
 *   <li>HTTP 状态只取 4xx 与 5xx。
 *   <li>每个枚举的单元测试调用 {@link ErrorCodes#verify(ErrorCode...)}，校验格式与唯一性。
 * </ul>
 *
 * <p>码值一经发布就是对外契约，只能新增，不能改名。
 */
public interface ErrorCode {

    /** 码值的格式：两段或以上的小写 snake_case，以点分隔。 */
    Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    /** problem+json 中 {@code type} 的前缀，与码值拼成稳定的 URN，不依赖任何可访问的域名。 */
    String TYPE_PREFIX = "urn:meridian:error:";

    /**
     * 返回码值，例如 {@code session.not_found}。
     *
     * @return 码值
     */
    String code();

    /**
     * 返回对应的 HTTP 状态码。
     *
     * @return 400 至 599 之间的状态码
     */
    int status();

    /**
     * 返回默认标题：同一码值的所有实例共用，不含具体实例信息；具体信息放在异常的 detail 中。
     *
     * @return 简短的中文标题
     */
    String title();

    /**
     * 返回 problem+json 的 {@code type}，形如 {@code urn:meridian:error:session.not_found}。
     *
     * @return 错误类型 URI
     */
    default URI type() {
        return URI.create(TYPE_PREFIX + code());
    }
}
