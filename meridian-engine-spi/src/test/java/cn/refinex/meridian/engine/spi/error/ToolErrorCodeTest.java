package cn.refinex.meridian.engine.spi.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.refinex.meridian.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** {@link ToolErrorCode} 的码值格式与唯一性，以及 MCP 不可用异常的可重试性。 */
class ToolErrorCodeTest {

    @Test
    void codesAreWellFormedAndUnique() {
        assertThatCode(() -> ErrorCodes.verify(ToolErrorCode.values())).doesNotThrowAnyException();
        assertThat(ToolErrorCode.values()).allSatisfy(c -> assertThat(c.code()).startsWith("tool."));
    }

    @Test
    void mcpUnavailableIsRetryable() {
        McpUnavailableException error = new McpUnavailableException("MCP 服务 crm 不可用", new IllegalStateException("x"));
        assertThat(error).isInstanceOf(RetryableTurnException.class);
        assertThat(error.errorCode()).isEqualTo(ToolErrorCode.MCP_UNAVAILABLE);
    }
}
