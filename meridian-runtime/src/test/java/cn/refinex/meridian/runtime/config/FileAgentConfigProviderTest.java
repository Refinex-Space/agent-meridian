package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 开发配置目录中的 YAML 解析为 engine-spi 的智能体版本与环境（T06 的 {@code FileAgentConfigProvider}）。 */
class FileAgentConfigProviderTest {

    @TempDir
    Path directory;

    @Test
    void agentVersionsAndEnvironmentsAreFoundByKindAndId() throws IOException {
        Files.writeString(directory.resolve("agent.yaml"), """
                kind: agent_version
                agent_id: agt_1
                agent_version_id: agv_1
                name: assistant
                model: {model_id: mdl_1, temperature: 0.2}
                system_prompt: 你好
                permissions:
                  mode: accept_edits
                  rules:
                    - {tool_name: read_file, rule_content: "reports/**", behavior: allow, layer: agent_version}
                compaction: {reserved_tokens: 1000}
                knowledge: {knowledge_base_ids: [], prefetch: false, prefetch_top_k: 5}
                max_iters: 20
                plan_mode: false
                expose_thinking: false
                unknown_field: 被忽略
                """);
        Files.writeString(directory.resolve("env.yml"), """
                kind: environment
                environment_id: env_1
                revision: 2
                tier: sandbox
                size: small
                egress_allowlist: [docs.example.com]
                """);
        Files.writeString(directory.resolve("notes.txt"), "不是 YAML，被忽略");
        FileAgentConfigProvider provider = new FileAgentConfigProvider(directory);

        ResolvedAgentSpec agent = provider.agentVersion("agv_1");
        EnvironmentSpec environment = provider.environment("env_1", 2);

        assertThat(agent.name()).isEqualTo("assistant");
        assertThat(agent.model().temperature()).isEqualTo(0.2);
        assertThat(agent.maxIters()).isEqualTo(20);
        assertThat(agent.permissions().mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(agent.permissions().rules().getFirst().behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(environment.tier()).isEqualTo(EnvironmentSpec.Tier.SANDBOX);
        assertThat(environment.size()).isEqualTo(EnvironmentSpec.SandboxSize.SMALL);
        assertThat(environment.egressAllowlist()).containsExactly("docs.example.com");
        assertThatThrownBy(() -> provider.environment("env_1", 1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> provider.agentVersion("agv_missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void missingDirectoryHasNoConfiguration() {
        FileAgentConfigProvider provider = new FileAgentConfigProvider(directory.resolve("absent"));

        assertThatThrownBy(() -> provider.agentVersion("agv_1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theCheckedInDevelopmentConfigurationParses() {
        // 测试的工作目录是模块目录，仓库根目录的 dev-config 在上一级
        FileAgentConfigProvider provider = new FileAgentConfigProvider(Path.of("..", "dev-config", "agents"));

        assertThat(provider.agentVersion("agv_dev_assistant_1").maxIters()).isEqualTo(50);
        assertThat(provider.environment("env_dev_none", 1).tier()).isEqualTo(EnvironmentSpec.Tier.NONE);
    }
}
