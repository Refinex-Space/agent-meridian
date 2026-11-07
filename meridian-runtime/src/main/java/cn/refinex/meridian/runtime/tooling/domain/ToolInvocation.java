package cn.refinex.meridian.runtime.tooling.domain;

import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import java.time.Instant;

/**
 * 工具账本中的一次工具执行（{@code rt.tool_invocations} 的一行，架构设计「核心表」）。
 *
 * @param id 账本记录 ID（{@code tiv_…}）
 * @param turnId 发起调用的轮次（子线程的调用记在父轮次下）
 * @param threadId 执行工具的线程
 * @param leaseEpoch 发起调用的那次执行的租约 epoch
 * @param toolUseId 模型给出的工具调用 ID
 * @param toolName 工具名
 * @param sideEffect 副作用等级
 * @param idempotencyKey 幂等键；{@code non_idempotent} 必有，其余可为空
 * @param status 状态；进程被强杀后仍为 {@link Status#RUNNING} 的调用，恢复时视为状态未知
 * @param inputJson 入参 JSON
 * @param resultRef 结果在对象存储中的引用；可为空
 * @param startedAt 开始时间
 * @param finishedAt 结束时间；可为空，表示未结束
 */
public record ToolInvocation(
        String id,
        String turnId,
        String threadId,
        long leaseEpoch,
        String toolUseId,
        String toolName,
        SideEffect sideEffect,
        String idempotencyKey,
        Status status,
        String inputJson,
        BlobRef resultRef,
        Instant startedAt,
        Instant finishedAt) {}
