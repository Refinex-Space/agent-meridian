package cn.refinex.meridian.runtime.session.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.ContractValidator;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 契约测试（T07-A09）：运行中的服务对规范中每个端点的响应都符合 {@code meridian-v1.yaml}，请求按规范构造并同样校验；
 * 事件流的每一帧按事件信封 schema 校验。最后核对规范中的全部操作都被校验过。
 */
class SessionApiContractIT extends ApiTestBase {

    @Test
    void everyEndpointConformsToTheSpec() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);

        ApiResponse created = api.createSession("""
                {"agent": "agt_dev_assistant", "environment": "env_dev_none", "end_user_id": "u_contract",
                 "end_user_attributes": {"acl_tags": ["dept:finance", "level:2"]}, "interaction": "attended",
                 "metadata": {"source": "contract"},
                 "custom_tools": [{"name": "lookup_order", "description": "查询订单", "side_effect": "none",
                                   "input_schema": {"type": "object", "properties": {"id": {"type": "string"}}}}]}
                """, "contract-" + System.nanoTime());
        assertThat(created.status()).isEqualTo(201);
        String sessionId = created.text("id");

        assertThat(api.listSessions("limit=5&end_user_id=u_contract").status()).isEqualTo(200);
        assertThat(api.getSession(sessionId).status()).isEqualTo(200);
        assertThat(api.updateSession(sessionId, "{\"metadata\": {\"source\": \"contract-2\"}}")
                        .status())
                .isEqualTo(200);

        ApiResponse sent = api.sendEvents(sessionId, """
                {"events": [
                  {"type": "user.message", "delivery": "queue",
                   "content": [{"type": "text", "text": "看看这些"},
                               {"type": "image", "url": "https://example.com/a.png", "media_type": "image/png"},
                               {"type": "image", "file_id": "fil_01JC8Z5A0000000000000000AA"},
                               {"type": "file", "file_id": "fil_01JC8Z5A0000000000000000AB", "filename": "a.pdf"}]},
                  {"type": "user.interrupt"}
                ]}
                """, "contract-events-" + System.nanoTime());
        assertThat(sent.status()).isEqualTo(202);
        assertThat(api.listEvents(sessionId, "limit=1").body().get("has_more").asBoolean())
                .isTrue();
        assertThat(api.listThreads(sessionId, "limit=10").status()).isEqualTo(200);
        assertThat(api.listPendingActions(sessionId, "status=pending").status()).isEqualTo(200);

        ApiResponse archived = api.archiveSession(sessionId, "contract-archive-" + System.nanoTime());
        assertThat(archived.status()).isEqualTo(200);
        // 已终止的会话：补发完历史事件后服务端关闭连接，事件流是有限的
        List<Frame> frames =
                api.finiteStream("GET /v1/sessions/{session_id}/stream", "/v1/sessions/" + sessionId + "/stream", null);
        assertThat(frames)
                .extracting(Frame::event)
                .containsExactly("user.message", "user.interrupt", "session.status_terminated");
        String mainThread = api.listThreads(sessionId, null)
                .body()
                .get("data")
                .get(0)
                .get("id")
                .asString();
        List<Frame> threadFrames = api.finiteStream(
                "GET /v1/sessions/{session_id}/threads/{thread_id}/stream",
                "/v1/sessions/" + sessionId + "/threads/" + mainThread + "/stream?event_deltas=true",
                "1");
        assertThat(threadFrames).extracting(Frame::event).containsExactly("user.interrupt");
        assertThat(api.finiteStream(
                        "GET /v1/sessions/{session_id}/stream",
                        "/v1/sessions/" + sessionId + "/stream?threads=main",
                        "3"))
                .isEmpty();

        assertThat(api.deleteSession(sessionId).status()).isEqualTo(202);

        // 错误响应同样按规范校验
        assertThat(api(null)
                        .callInvalid(
                                "GET /v1/sessions/{session_id}/pending-actions",
                                HttpMethod.GET,
                                "/v1/sessions/" + sessionId + "/pending-actions",
                                null,
                                Map.of())
                        .status())
                .isEqualTo(401);
        assertThat(api.call(
                                "GET /v1/sessions/{session_id}/threads/{thread_id}/stream",
                                HttpMethod.GET,
                                "/v1/sessions/" + sessionId + "/threads/thr_01JC8Z5A0000000000000000AA/stream",
                                null,
                                null)
                        .text("code"))
                .isEqualTo("thread.not_found");

        // 模型直通：本容器没有登记模型，响应是规范中的 404；成功路径的契约在 ModelEndpointsIT
        assertThat(api.call(
                                "POST /v1/chat/completions",
                                HttpMethod.POST,
                                "/v1/chat/completions",
                                "{\"model\": \"mdl_absent\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}",
                                null)
                        .text("code"))
                .isEqualTo("model.not_found");
        assertThat(api.call(
                                "POST /v1/embeddings",
                                HttpMethod.POST,
                                "/v1/embeddings",
                                "{\"model\": \"mdl_absent\", \"input\": \"你好\"}",
                                null)
                        .text("code"))
                .isEqualTo("model.not_found");

        // Webhook 投递记录：本项目没有订阅，列表为空，单条与重投是规范中的 404；成功路径的契约在 WebhookDeliveryIT
        assertThat(api.call(
                                "GET /v1/webhook-deliveries",
                                HttpMethod.GET,
                                "/v1/webhook-deliveries?limit=5&session_id=" + sessionId,
                                null,
                                null)
                        .status())
                .isEqualTo(200);
        String absentDelivery = "whd_01JC8Z5A0000000000000000AA";
        assertThat(api.call(
                                "GET /v1/webhook-deliveries/{delivery_id}",
                                HttpMethod.GET,
                                "/v1/webhook-deliveries/" + absentDelivery,
                                null,
                                null)
                        .text("code"))
                .isEqualTo("webhook_delivery.not_found");
        assertThat(api.call(
                                "POST /v1/webhook-deliveries/{delivery_id}:redeliver",
                                HttpMethod.POST,
                                "/v1/webhook-deliveries/" + absentDelivery + ":redeliver",
                                null,
                                null)
                        .text("code"))
                .isEqualTo("webhook_delivery.not_found");

        assertThat(ContractValidator.validatedOperations()).containsAll(ContractValidator.allOperations());
        assertThat(ContractValidator.allOperations()).hasSize(17);
    }
}
