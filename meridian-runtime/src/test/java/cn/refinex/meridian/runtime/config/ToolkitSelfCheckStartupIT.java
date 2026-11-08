package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 启动自检（T10a-A01，登记事项 D02）：工具提供者给出白名单外的非 {@code ToolBase} 工具时，runtime 启动失败；生产的提供者
 * 能正常启动（{@code RuntimeApplicationIT}）。
 */
class ToolkitSelfCheckStartupIT {

    /** 不继承 ToolBase 的工具：AgentScope 的权限引擎对它一律放行。 */
    static final AgentTool RAW_TOOL = new AgentTool() {
        @Override
        public String getName() {
            return "raw_shell";
        }

        @Override
        public String getDescription() {
            return "绕过权限引擎的工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("不应执行"));
        }
    };

    /** 用不受权限约束的工具替换平台工具提供者。 */
    @Configuration(proxyBeanMethods = false)
    static class RawTools {

        @Bean
        ToolProvider toolProvider() {
            return profile -> ToolProvider.ToolBundle.of(List.of(RAW_TOOL));
        }
    }

    @Test
    void aNonToolBaseToolOutsideTheWhitelistStopsTheRuntimeFromStarting() {
        Map<String, Object> properties = new LinkedHashMap<>();
        SpringContainerProperties.postgres((name, value) -> properties.put(name, value.get()));
        SpringContainerProperties.redis((name, value) -> properties.put(name, value.get()));
        SpringContainerProperties.minio((name, value) -> properties.put(name, value.get()));

        SpringApplicationBuilder application = new SpringApplicationBuilder(RuntimeApplication.class, RawTools.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties(properties);

        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext ignored = application.run()) {
                        assertThat(ignored).as("不应启动成功").isNull();
                    }
                })
                .hasStackTraceContaining("Toolkit 自检失败")
                .hasStackTraceContaining("raw_shell");
    }
}
