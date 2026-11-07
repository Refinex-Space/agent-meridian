package cn.refinex.meridian.runtime.session.domain;

import java.util.List;

/**
 * 终端用户属性（{@code sessions.end_user_attributes}），由业务系统在创建会话时提供，平台据此做数据权限过滤。
 *
 * @param aclTags 知识库检索的访问标签，例如 {@code dept:finance}；没有时为空列表
 */
public record EndUserAttributes(List<String> aclTags) {

    /** 没有任何属性。 */
    public static final EndUserAttributes NONE = new EndUserAttributes(List.of());

    /** 对标签列表做不可变复制。 */
    public EndUserAttributes {
        aclTags = aclTags == null ? List.of() : List.copyOf(aclTags);
    }
}
