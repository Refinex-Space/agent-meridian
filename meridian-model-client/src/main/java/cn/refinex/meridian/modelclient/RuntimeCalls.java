package cn.refinex.meridian.modelclient;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * 三个客户端共用的一次 POST：runtime 的拒绝按 problem+json 的 {@code code} 转成 {@link ModelClientException}，连接失败与空响应
 * 视为可重试。
 */
final class RuntimeCalls {

    private RuntimeCalls() {}

    /**
     * 发送请求并读出响应体。
     *
     * @param runtime 指向 runtime、带服务 JWT 的客户端
     * @param path 内部接口路径
     * @param body 请求体
     * @param type 响应体类型
     * @param <T> 响应体类型
     * @return 响应体，不为空
     * @throws ModelClientException runtime 拒绝请求、不可用或响应为空时
     */
    static <T> T post(RestClient runtime, String path, Object body, Class<T> type) {
        T response;
        try {
            response = runtime.post().uri(path).body(body).retrieve().body(type);
        } catch (RestClientResponseException e) {
            throw ModelClientException.fromResponse(e.getStatusCode(), problemCode(e), problemDetail(e));
        } catch (RestClientException e) {
            throw new ModelClientException(
                    true, 0, ModelClientException.UNAVAILABLE, "runtime 不可用：" + e.getMessage(), e);
        }
        if (response == null) {
            throw incomplete("runtime 返回了空响应");
        }
        return response;
    }

    /**
     * 响应不完整（条数、序号不对）：按 runtime 暂时不可用处理，可以重试。
     *
     * @param message 说明
     * @return 异常
     */
    static ModelClientException incomplete(String message) {
        return new ModelClientException(true, 0, ModelClientException.UNAVAILABLE, message, null);
    }

    /**
     * 状态码是否可以重试。
     *
     * @param status 状态码
     * @return 429 与 5xx 为 true
     */
    static boolean retryable(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private static String problemCode(RestClientResponseException e) {
        JsonNode problem = problem(e);
        return problem == null ? null : problem.path("code").asString(null);
    }

    private static String problemDetail(RestClientResponseException e) {
        JsonNode problem = problem(e);
        String detail = problem == null ? null : problem.path("detail").asString(null);
        return detail != null ? detail : e.getStatusText();
    }

    private static JsonNode problem(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(JsonNode.class);
        } catch (RuntimeException ignored) {
            // 响应体不是 problem+json（例如网关返回的 HTML）：只按状态码判断
            return null;
        }
    }
}
