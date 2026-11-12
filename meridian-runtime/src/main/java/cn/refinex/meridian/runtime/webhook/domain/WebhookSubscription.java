package cn.refinex.meridian.runtime.webhook.domain;

import java.util.Objects;
import java.util.Set;

/**
 * 项目的一个 Webhook（control-plane 下发，ADR-0049）。
 *
 * @param id Webhook ID（{@code whk_…}）
 * @param url 接收方地址
 * @param events 订阅的事件类型取值
 * @param enabled 是否启用
 * @param signingSecret 签名密钥明文；{@link #toString()} 不输出它
 */
public record WebhookSubscription(String id, String url, Set<String> events, boolean enabled, String signingSecret) {

    /**
     * 创建订阅。
     *
     * @throws NullPointerException 缺少字段时
     */
    public WebhookSubscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        events = Set.copyOf(events);
        Objects.requireNonNull(signingSecret, "signingSecret");
    }

    /**
     * 是否为该事件生成投递：启用且订阅了它。
     *
     * @param eventType 事件类型取值
     * @return 是时为 true
     */
    public boolean wants(String eventType) {
        return enabled && events.contains(eventType);
    }

    @Override
    public String toString() {
        return "WebhookSubscription[id=" + id + ", url=" + url + ", events=" + events + ", enabled=" + enabled
                + ", signingSecret=***]";
    }
}
