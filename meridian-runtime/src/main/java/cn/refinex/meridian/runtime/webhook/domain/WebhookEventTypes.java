package cn.refinex.meridian.runtime.webhook.domain;

import cn.refinex.meridian.engine.spi.event.EventType;
import java.util.Set;

/** 可订阅的事件类型（架构设计「Webhook」）。 */
public final class WebhookEventTypes {

    /** 四种可订阅的事件。 */
    public static final Set<EventType> SUBSCRIBABLE = Set.of(
            EventType.SESSION_STATUS_IDLE,
            EventType.SESSION_STATUS_REQUIRES_ACTION,
            EventType.SESSION_ERROR,
            EventType.SESSION_STATUS_TERMINATED);

    private WebhookEventTypes() {}

    /**
     * 事件是否可订阅。
     *
     * @param type 事件类型
     * @return 可订阅时为 true
     */
    public static boolean subscribable(EventType type) {
        return SUBSCRIBABLE.contains(type);
    }
}
