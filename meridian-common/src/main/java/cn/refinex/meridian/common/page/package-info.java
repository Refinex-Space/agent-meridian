/**
 * 游标分页：{@link cn.refinex.meridian.common.page.Cursor}（不透明游标）、
 * {@link cn.refinex.meridian.common.page.PageRequest}（{@code limit}、{@code after}）与
 * {@link cn.refinex.meridian.common.page.PageResult}（{@code data}、{@code has_more}、{@code next_cursor}）。
 *
 * <p>只定义参数与结果的形状和编码；排序键的选择与 SQL 由各仓储负责。
 */
package cn.refinex.meridian.common.page;
