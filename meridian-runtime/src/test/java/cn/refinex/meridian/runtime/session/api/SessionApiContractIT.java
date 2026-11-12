package cn.refinex.meridian.runtime.session.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.ContractValidator;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import java.nio.charset.StandardCharsets;
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

        // 文件（T17b）：上传、查看、下载，再在消息中引用
        ApiResponse image = api.uploadFile("pixel.png", PNG, "contract-file-" + System.nanoTime());
        assertThat(image.status()).isEqualTo(201);
        assertThat(image.text("media_type")).isEqualTo("image/png");
        ApiResponse document = api.uploadFile("a.txt", "合同正文".getBytes(StandardCharsets.UTF_8), null);
        String imageId = image.text("id");
        String documentId = document.text("id");
        assertThat(api.call("GET /v1/files/{file_id}", HttpMethod.GET, "/v1/files/" + documentId, null, null)
                        .text("filename"))
                .isEqualTo("a.txt");
        assertThat(api.binary(
                                "GET /v1/files/{file_id}/content",
                                HttpMethod.GET,
                                "/v1/files/" + imageId + "/content",
                                null)
                        .bytes())
                .isEqualTo(PNG);

        ApiResponse sent =
                api.sendEvents(sessionId, """
                {"events": [
                  {"type": "user.message", "delivery": "queue",
                   "content": [{"type": "text", "text": "看看这些"},
                               {"type": "image", "url": "https://example.com/a.png", "media_type": "image/png"},
                               {"type": "image", "file_id": "%s"},
                               {"type": "file", "file_id": "%s", "filename": "a.pdf"}]},
                  {"type": "user.interrupt"}
                ]}
                """.formatted(imageId, documentId), "contract-events-" + System.nanoTime());
        assertThat(sent.status()).isEqualTo(202);
        assertThat(api.listEvents(sessionId, "limit=1").body().get("has_more").asBoolean())
                .isTrue();
        assertThat(api.listThreads(sessionId, "limit=10").status()).isEqualTo(200);
        assertThat(api.listPendingActions(sessionId, "status=pending").status()).isEqualTo(200);

        // 工作区（T17b）：none 档写入、读取与列表
        String workspacePath = "/v1/sessions/" + sessionId + "/workspace/files/notes%2Fplan.md";
        assertThat(api.binary(
                                "PUT /v1/sessions/{session_id}/workspace/files/{path}",
                                HttpMethod.PUT,
                                workspacePath,
                                "# 计划".getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo(200);
        assertThat(new String(
                        api.binary(
                                        "GET /v1/sessions/{session_id}/workspace/files/{path}",
                                        HttpMethod.GET,
                                        workspacePath,
                                        null)
                                .bytes(),
                        StandardCharsets.UTF_8))
                .isEqualTo("# 计划");
        assertThat(api.call(
                                "GET /v1/sessions/{session_id}/workspace:list",
                                HttpMethod.GET,
                                "/v1/sessions/" + sessionId + "/workspace:list?prefix=notes/&limit=10",
                                null,
                                null)
                        .body()
                        .get("data")
                        .get(0)
                        .get("path")
                        .asString())
                .isEqualTo("notes/plan.md");

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
                                "POST /v1/responses",
                                HttpMethod.POST,
                                "/v1/responses",
                                "{\"model\": \"mdl_absent\", \"input\": \"你好\"}",
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
        assertThat(ContractValidator.allOperations()).hasSize(24);
    }

    /** 1×1 的 PNG。 */
    private static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");
}
