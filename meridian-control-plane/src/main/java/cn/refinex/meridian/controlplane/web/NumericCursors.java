package cn.refinex.meridian.controlplane.web;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.page.PageRequest;

/**
 * 按序号分页的游标（智能体版本、环境修订、技能版本按序号排序，ADR-0047）：游标的第一个键是上一页最后一条的序号。
 */
public final class NumericCursors {

    private NumericCursors() {}

    /**
     * 取游标中的序号。
     *
     * @param page 分页参数
     * @return 序号；第一页时为空
     * @throws ValidationException 游标的键不是数字时（{@code request.invalid_cursor}）
     */
    public static Long after(PageRequest page) {
        if (page.after() == null) {
            return null;
        }
        try {
            return Long.valueOf(page.after().key(0));
        } catch (NumberFormatException e) {
            throw new ValidationException(CommonErrorCode.INVALID_CURSOR, "分页游标无效");
        }
    }
}
