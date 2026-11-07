package cn.refinex.meridian.runtime.session.domain;

import java.util.Objects;

/**
 * 新建线程的参数。主线程的父线程与父工具调用都为空；子线程两者都必填。
 *
 * @param id 线程 ID（{@code thr_…}）；必填
 * @param sessionId 所属会话；必填
 * @param parentThreadId 父线程 ID；可为空，表示主线程
 * @param parentToolUseId 拉起子线程的工具调用 ID；可为空，表示主线程
 * @param agentName 执行本线程的智能体名；必填
 */
public record NewThread(String id, String sessionId, String parentThreadId, String parentToolUseId, String agentName) {

    /**
     * 创建参数。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 父线程与父工具调用只给了一个时
     */
    public NewThread {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agentName, "agentName");
        if ((parentThreadId == null) != (parentToolUseId == null)) {
            throw new IllegalArgumentException("子线程必须同时给出父线程与父工具调用：" + id);
        }
    }

    /**
     * 主线程的参数。
     *
     * @param id 线程 ID
     * @param sessionId 所属会话
     * @param agentName 智能体名
     * @return 参数
     */
    public static NewThread main(String id, String sessionId, String agentName) {
        return new NewThread(id, sessionId, null, null, agentName);
    }
}
