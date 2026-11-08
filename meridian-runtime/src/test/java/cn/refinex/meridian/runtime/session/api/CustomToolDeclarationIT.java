package cn.refinex.meridian.runtime.session.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * T08-A08：创建会话时校验自定义工具的声明（架构设计「待处理动作」中的"自定义工具"）。名称必须匹配
 * {@code ^[a-z][a-z0-9_]{0,63}$}，且不能与保留工具名重名：平台工具名、harness 仍然注册的工具名（{@code plan_enter} 等），以及
 * {@code mcp__} 前缀。挂起、回传与续跑见 {@code ApprovalFlowIT}。
 */
class CustomToolDeclarationIT extends ApiTestBase {

    @Test
    void namesThatDoNotMatchThePatternAreRejected() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        for (String name : new String[] {"LookupOrder", "1lookup", "lookup-order", "a".repeat(65)}) {
            ApiResponse response =
                    api.callInvalid("POST /v1/sessions", HttpMethod.POST, "/v1/sessions", body(name), Map.of());

            assertThat(response.status()).as(name).isEqualTo(400);
            assertThat(response.text("code")).as(name).isEqualTo("request.validation_failed");
        }
    }

    @Test
    void reservedToolNamesAreRejected() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        for (String name : new String[] {
            "bash",
            "read_file",
            "spawn_subagent",
            "plan_enter",
            "load_skill_through_path",
            "wait_async_results",
            "mcp__crm__query"
        }) {
            ApiResponse response = api.createSession(body(name), null);

            assertThat(response.status()).as(name).isEqualTo(400);
            assertThat(response.text("code")).as(name).isEqualTo("request.validation_failed");
            assertThat(response.body().get("errors").get(0).get("field").asString())
                    .isEqualTo("custom_tools");
            assertThat(response.body().get("errors").get(0).get("message").asString())
                    .contains(name);
        }
    }

    @Test
    void aLegalNameIsAccepted() {
        ApiResponse response = api(ApiTestKeys.KEY_A).createSession(body("notify_customer"), null);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("custom_tools").get(0).get("name").asString())
                .isEqualTo("notify_customer");
    }

    private static String body(String toolName) {
        return """
                {"agent": "%s", "environment": "%s",
                 "custom_tools": [{"name": "%s", "description": "测试工具", "input_schema": {"type": "object"}}]}
                """.formatted(AGENT, ENVIRONMENT, toolName);
    }
}
