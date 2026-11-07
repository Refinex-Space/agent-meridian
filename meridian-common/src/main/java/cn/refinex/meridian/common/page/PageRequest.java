package cn.refinex.meridian.common.page;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import java.util.List;

/**
 * 一次游标分页查询的参数，对应 HTTP 查询参数 {@code ?limit=&after=}。
 *
 * @param limit 每页条数，1 至 {@link #MAX_LIMIT}
 * @param after 从哪条记录之后开始读；可为空，表示第一页
 */
public record PageRequest(int limit, Cursor after) {

    /** 未指定 {@code limit} 时的每页条数。架构设计未规定，取常见 API 的默认值。 */
    public static final int DEFAULT_LIMIT = 20;

    /** 每页条数上限，防止单次查询过大。 */
    public static final int MAX_LIMIT = 100;

    /**
     * 创建分页参数。
     *
     * @throws IllegalArgumentException {@code limit} 越界时；来自 HTTP 的输入用 {@link #of(Integer, String)} 校验
     */
    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 应在 1 至 " + MAX_LIMIT + " 之间：" + limit);
        }
    }

    /**
     * 第一页。
     *
     * @param limit 每页条数
     * @return 分页参数
     */
    public static PageRequest first(int limit) {
        return new PageRequest(limit, null);
    }

    /**
     * 从 HTTP 查询参数构造并校验分页参数。
     *
     * @param limit {@code limit} 参数，可为空，表示取 {@link #DEFAULT_LIMIT}
     * @param after {@code after} 参数，可为空或空白，表示第一页
     * @return 分页参数
     * @throws ValidationException {@code limit} 越界或游标无效时
     */
    public static PageRequest of(Integer limit, String after) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "limit 应在 1 至 " + MAX_LIMIT + " 之间",
                    List.of(new FieldViolation("limit", "应在 1 至 " + MAX_LIMIT + " 之间")));
        }
        Cursor cursor = after == null || after.isBlank() ? null : Cursor.decode(after);
        return new PageRequest(effectiveLimit, cursor);
    }

    /**
     * 查询时应取的行数：比 {@code limit} 多一行，用来判断是否还有下一页，见 {@link PageResult#of}。
     *
     * @return {@code limit + 1}
     */
    public int fetchSize() {
        return limit + 1;
    }
}
