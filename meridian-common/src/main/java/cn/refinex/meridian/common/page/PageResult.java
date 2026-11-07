package cn.refinex.meridian.common.page;

import java.util.List;
import java.util.function.Function;

/**
 * 一页查询结果，序列化为 {@code {data, has_more, next_cursor}}（架构设计「API 设计」中的分页约定）。
 *
 * @param data 本页数据，不超过请求的 {@code limit} 条
 * @param hasMore 是否还有下一页
 * @param nextCursor 下一页的游标；可为空，没有下一页时为空
 * @param <T> 数据类型
 */
public record PageResult<T>(List<T> data, boolean hasMore, String nextCursor) {

    /** 复制数据列表，保证结果不可变。 */
    public PageResult {
        data = List.copyOf(data);
    }

    /**
     * 由多取一行的查询结果构造一页：查询按 {@link PageRequest#fetchSize()} 取行，
     * 多出的一行只用来判断是否还有下一页，不返回给调用方。
     *
     * @param rows 查询结果，最多 {@code limit + 1} 行，已按排序键排好序
     * @param request 分页参数
     * @param cursorOf 从一行数据取出排序键
     * @param <T> 数据类型
     * @return 一页结果
     */
    public static <T> PageResult<T> of(List<T> rows, PageRequest request, Function<T, Cursor> cursorOf) {
        if (rows.size() <= request.limit()) {
            return new PageResult<>(rows, false, null);
        }
        List<T> page = rows.subList(0, request.limit());
        return new PageResult<>(page, true, cursorOf.apply(page.getLast()).encode());
    }

    /**
     * 转换数据类型，保留分页信息，例如把领域对象映射为 DTO。
     *
     * @param mapper 转换函数
     * @param <R> 目标类型
     * @return 新的一页结果
     */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(data.stream().map(mapper).toList(), hasMore, nextCursor);
    }
}
