package cn.refinex.meridian.engine.spi.driver;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 沙箱的创建、恢复、快照、删除，内部使用 AgentScope Kubernetes 扩展的 {@code SandboxClient}，由
 * {@code meridian-engine-agentscope} 的 {@code sandbox} 包实现，见架构设计「端口」「会话沙箱」。
 *
 * <p>何时创建、何时回收、状态存在哪里由 runtime 的 {@code SandboxService} 决定；驱动只执行具体操作，所有操作都是
 * 阻塞的远程调用，由实现调度到虚拟线程。
 */
public interface SandboxDriver {

    /** 技能资源在沙箱中的目录，相对工作区根（架构设计「会话沙箱」"技能资源"）。 */
    String SKILLS_DIR = ".meridian/skills";

    /** 产物目录，相对工作区根（架构设计「会话沙箱」"产物"）。 */
    String OUTPUTS_DIR = "outputs";

    /**
     * 从对应规格的预热池认领一个新沙箱。
     *
     * @param request 创建请求
     * @return 新沙箱的句柄
     */
    Mono<SandboxHandle> create(SandboxRequest request);

    /**
     * 按序列化状态重新连接一个仍在运行的沙箱。
     *
     * @param state 序列化后的沙箱状态
     * @return 沙箱句柄；沙箱已不存在时以 {@code NotFoundException} 失败，调用方改为从快照恢复
     */
    Mono<SandboxHandle> resume(String state);

    /**
     * 新建沙箱并从快照恢复工作区，用于空闲回收之后的下一轮。
     *
     * @param request 创建请求
     * @param snapshotKey 快照在对象存储中的键
     * @return 新沙箱的句柄
     */
    Mono<SandboxHandle> restore(SandboxRequest request, String snapshotKey);

    /**
     * 把沙箱的工作区打成快照存入对象存储，空闲回收前调用。
     *
     * @param handle 沙箱句柄
     * @return 快照在对象存储中的键
     */
    Mono<String> snapshot(SandboxHandle handle);

    /**
     * 删除沙箱。沙箱已不存在时无副作用。
     *
     * @param handle 沙箱句柄
     * @return 删除后完成
     */
    Mono<Void> delete(SandboxHandle handle);

    /**
     * 删除带该会话标签的全部沙箱（ADR-0049）：清理已删除的会话时兜底，覆盖认领之后、记进会话之前进程崩溃留下的沙箱（登记事项 D37
     * 的已删除会话部分）。没有这样的沙箱时无副作用。
     *
     * @param sessionId 会话 ID
     * @return 删除的沙箱数
     */
    Mono<Integer> deleteSessionSandboxes(String sessionId);

    /**
     * 让沙箱中的技能资源与给定技能一致：按内容摘要比较 {@value #SKILLS_DIR} 下的全部文件，不一致（含缺失、被篡改、多出文件）时清空该目录
     * 并用 tar 重新写入。每个技能写在 {@code {SKILLS_DIR}/{技能名}/} 下，不含技能正文 {@code SKILL.md}（正文经平台技能仓库加载）。
     *
     * @param handle 沙箱句柄
     * @param skills 智能体版本引用的技能
     * @return 是否重新写入了
     */
    Mono<Boolean> syncSkills(SandboxHandle handle, List<SkillDefinition> skills);

    /**
     * 列出产物目录 {@value #OUTPUTS_DIR} 下的全部普通文件（不跟随符号链接），带大小与 SHA-256。
     *
     * @param handle 沙箱句柄
     * @return 文件清单；目录不存在时为空
     */
    Mono<List<OutputFile>> listOutputs(SandboxHandle handle);

    /**
     * 读取一个产物文件的全部内容。
     *
     * @param handle 沙箱句柄
     * @param path 相对产物目录的路径（{@link OutputFile#path()}）
     * @return 内容
     */
    Mono<byte[]> readOutput(SandboxHandle handle, String path);

    /**
     * 产物目录中的一个文件。
     *
     * @param path 相对产物目录的路径，分隔符为 {@code /}；必填
     * @param size 字节数
     * @param sha256 内容的 SHA-256（小写十六进制）；必填
     */
    record OutputFile(String path, long size, String sha256) {

        /**
         * 创建产物文件。
         *
         * @throws NullPointerException 缺少路径或摘要时
         * @throws IllegalArgumentException 路径为空白、以 {@code /} 开头或含 {@code ..} 段时
         */
        public OutputFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            if (path.isBlank()
                    || path.startsWith("/")
                    || List.of(path.split("/")).contains("..")) {
                throw new IllegalArgumentException("产物路径不合法：" + path);
            }
        }
    }

    /**
     * 创建沙箱的请求。
     *
     * @param sessionId 会话 ID，记入沙箱标签便于排查；必填
     * @param size 资源规格，决定使用哪个模板与预热池；必填
     */
    record SandboxRequest(String sessionId, SandboxSize size) {

        /**
         * 创建请求。
         *
         * @throws NullPointerException 缺少任一字段时
         */
        public SandboxRequest {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(size, "size");
        }
    }
}
