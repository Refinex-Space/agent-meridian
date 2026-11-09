/**
 * 两个档位的工作区文件系统（架构设计「文件系统装配」，ADR-0034、ADR-0037）：{@code none} 档的平台存储
 * （{@code MeridianBaseStore} 经 {@code WorkspaceStorePort}）、受保护路径的只读视图、技能仓库，{@code sandbox} 档平台工具的
 * 路径翻译与沙箱绑定。会话数据不落 runtime 本机磁盘。
 *
 * <p>不负责沙箱的获取与生命周期（{@code sandbox} 包与 runtime 的 {@code SandboxService}）。
 */
package cn.refinex.meridian.engine.agentscope.fs;
