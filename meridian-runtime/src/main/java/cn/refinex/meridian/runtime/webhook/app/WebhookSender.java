package cn.refinex.meridian.runtime.webhook.app;

import java.util.Map;

/**
 * 发出一次 Webhook 请求（ADR-0049）。实现负责地址校验（含连接时的解析结果）与超时，不跟随重定向、不走代理，不抛异常：失败放进结果。
 * 方法会阻塞，必须线程安全。
 */
@FunctionalInterface
public interface WebhookSender {

    /**
     * 以 {@code POST} 发出请求体。
     *
     * @param url 接收方地址
     * @param headers 请求头（签名等），{@code Content-Type} 由实现设置为 {@code application/json}
     * @param body 请求体原文
     * @return 结果
     */
    Result send(String url, Map<String, String> headers, String body);

    /**
     * 一次请求的结果。
     *
     * @param status 收到的 HTTP 状态码；没有收到响应时为空
     * @param error 失败的原因（英文短句）；成功时为空
     */
    record Result(Integer status, String error) {

        /**
         * 是否成功：收到 2xx。
         *
         * @return 成功时为 true
         */
        public boolean succeeded() {
            return status != null && status >= 200 && status < 300;
        }
    }
}
