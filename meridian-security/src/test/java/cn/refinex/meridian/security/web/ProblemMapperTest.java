package cn.refinex.meridian.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.server.ResponseStatusException;

class ProblemMapperTest {

    private final ProblemMapper mapper = new ProblemMapper();

    record CreateSession(String agentId, Integer maxTurns) {}

    @Test
    void bindingErrorsBecomeSnakeCaseFieldViolations() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new CreateSession(null, 0), "request");
        result.rejectValue("agentId", "NotBlank", "不能为空");
        result.rejectValue("maxTurns", "Min", "不能小于 1");

        Problem problem = mapper.map(new BindException(result), "req-1", "/v1/sessions");

        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.body()).containsEntry("code", "request.validation_failed");
        assertThat(problem.body().get("errors"))
                .isEqualTo(List.of(
                        Map.of("field", "agent_id", "message", "不能为空"),
                        Map.of("field", "max_turns", "message", "不能小于 1")));
    }

    @Test
    void unreadableBodyIsRequestInvalid() {
        HttpMessageNotReadableException error = new HttpMessageNotReadableException(
                "JSON parse error at line 1", new ServletServerHttpRequest(new MockHttpServletRequest()));

        Problem problem = mapper.map(error, "req-1", "/v1/sessions");

        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.body()).containsEntry("code", "request.invalid").containsEntry("detail", "请求体无法解析");
    }

    @Test
    void frameworkErrorsKeepTheirStatus() {
        assertThat(mapper.map(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED), "r", "/")
                        .body())
                .containsEntry("status", 405)
                .containsEntry("code", "request.method_not_allowed");
        assertThat(mapper.map(new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT), "r", "/")
                        .body())
                .containsEntry("status", 418)
                .containsEntry("code", "request.rejected");
        assertThat(mapper.map(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream 10.0.0.3"), "r", "/")
                        .body())
                .containsEntry("status", 502)
                .containsEntry("code", "internal.error")
                .containsEntry("detail", ProblemMapper.INTERNAL_DETAIL);
    }

    @Test
    void meridianExceptionsUseTheirOwnCode() {
        Problem problem =
                mapper.map(new ConflictException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, "键已用于不同请求"), "r", "/v1/x");

        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.body())
                .containsEntry("code", "idempotency.key_reused")
                .doesNotContainKey("errors");
    }

    @Test
    void snakeCaseConversionHandlesNestedPaths() {
        assertThat(ProblemMapper.toSnakeCase("metadata.inputTokens")).isEqualTo("metadata.input_tokens");
        assertThat(ProblemMapper.toSnakeCase("items[0].toolName")).isEqualTo("items[0].tool_name");
        assertThat(ProblemMapper.toSnakeCase("limit")).isEqualTo("limit");
    }
}
