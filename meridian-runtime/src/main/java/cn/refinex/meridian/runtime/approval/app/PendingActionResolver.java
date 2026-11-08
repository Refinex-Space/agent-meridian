package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.runtime.approval.domain.ActionConclusion;
import cn.refinex.meridian.runtime.approval.domain.ConclusionOrigin;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.RememberedRules;
import cn.refinex.meridian.runtime.approval.domain.ResolvableAction;
import cn.refinex.meridian.runtime.approval.domain.ResumptionPlanner;
import cn.refinex.meridian.runtime.approval.domain.ResumptionPlanner.Resumption;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.approval.infra.PermissionRuleRepository;
import cn.refinex.meridian.runtime.dispatch.app.EnqueueResult;
import cn.refinex.meridian.runtime.dispatch.app.TurnClaimer;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.app.TurnTransitions;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 待处理动作的结论与恢复轮（架构设计「待处理动作」「消息投递」「权限判定」）：
 *
 * <ul>
 *   <li>记录结论：调用方的回应（{@link #respond}）、等待回应时的中断（{@link #interrupt}）、超时（{@link #expire}），每个结论写一条
 *       审计；"记住此决定"写入会话级规则或经 {@link PolicyStorePort} 写入项目级规则，之后每轮合成时生效。
 *   <li>生成恢复轮（{@link #resumeIfComplete}）：一轮的全部动作都有了结论时，按 {@link ResumptionPlanner} 生成下一个恢复轮入队，
 *       等待回应的轮次结束为 {@code completed}，交出的结论记下消费它的恢复轮。
 * </ul>
 *
 * <p>所有方法都在调用方的事务中执行（发送事件、超时扫描、轮次结束），调用方先取线程的咨询锁：恢复轮的入队与 API 写入消息、
 * Worker 结束轮次串行（ADR-0028 第 8 条）。调用线程上要绑定会话所在项目的 {@code PlatformContext}。线程安全：无状态。
 */
@Service
public class PendingActionResolver {

    private static final Logger log = LoggerFactory.getLogger(PendingActionResolver.class);

    private final PendingActionRepository actions;
    private final PermissionRuleRepository rules;
    private final PolicyStorePort policies;
    private final TurnTransitions transitions;
    private final TurnEnqueuer enqueuer;
    private final TurnClaimer claimer;
    private final ApprovalAudit audit;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param actions 待处理动作仓储
     * @param rules 权限规则仓储，写入会话级规则
     * @param policies 项目策略，写入项目级规则
     * @param transitions 轮次迁移
     * @param enqueuer 入队
     * @param claimer 读取等待回应的轮次的输入
     * @param audit 审计
     * @param clock 时钟
     */
    public PendingActionResolver(
            PendingActionRepository actions,
            PermissionRuleRepository rules,
            PolicyStorePort policies,
            TurnTransitions transitions,
            TurnEnqueuer enqueuer,
            TurnClaimer claimer,
            ApprovalAudit audit,
            Clock clock) {
        this.actions = actions;
        this.rules = rules;
        this.policies = policies;
        this.transitions = transitions;
        this.enqueuer = enqueuer;
        this.claimer = claimer;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 记录调用方对一个动作的回应。动作已由调用方锁住并校验过类型与状态（{@code EventAdmission}）。
     *
     * @param action 被回应的动作
     * @param response 回应事件
     * @param decidedBy 做出决定的主体（API Key ID）；可为空
     */
    public void respond(StoredPendingAction action, UserEvent response, String decidedBy) {
        ActionConclusion conclusion = ActionConclusion.of(response);
        if (!actions.conclude(action.id(), PendingActionStatus.RESOLVED, conclusion, decidedBy, clock.instant())) {
            throw new IllegalStateException("待处理动作已不在等待：" + action.id());
        }
        remember(action, conclusion, decidedBy);
        audit.concluded(action, conclusion, decidedBy);
        log.info(
                "待处理动作已回应 actionId={} type={} turnId={}",
                action.id(),
                action.type().value(),
                action.turnId());
    }

    /**
     * 等待回应时收到 {@code user.interrupt}：对轮次的全部待处理动作生成拒绝结论（恢复确认视为 skip），之后的恢复轮带着中断请求
     * 运行一次后停止（架构设计「消息投递」）。
     *
     * @param turnId 等待回应的轮次
     * @return 生成结论的动作数
     */
    public int interrupt(String turnId) {
        int count = 0;
        Instant now = clock.instant();
        for (ResolvableAction resolvable : actions.lockByTurn(turnId)) {
            StoredPendingAction action = resolvable.action();
            if (!action.isPending()) {
                continue;
            }
            ActionConclusion conclusion = ActionConclusion.declined(action.type(), ConclusionOrigin.INTERRUPT);
            if (actions.conclude(action.id(), PendingActionStatus.RESOLVED, conclusion, null, now)) {
                audit.concluded(action, conclusion, null);
                count++;
            }
        }
        log.info("等待回应时收到中断，全部待处理动作视为拒绝 turnId={} actions={}", turnId, count);
        return count;
    }

    /**
     * 一个动作超时：按类型生成结论（工具确认视为拒绝，自定义工具以 {@code timeout} 错误回传，恢复确认视为 skip），状态为
     * {@code expired}。动作已由调用方锁住。
     *
     * @param action 超时的动作
     * @return 动作仍在等待、结论已写入时为 true
     */
    public boolean expire(StoredPendingAction action) {
        ActionConclusion conclusion = ActionConclusion.declined(action.type(), ConclusionOrigin.TIMEOUT);
        if (!actions.conclude(action.id(), PendingActionStatus.EXPIRED, conclusion, null, clock.instant())) {
            return false;
        }
        audit.concluded(action, conclusion, null);
        log.info(
                "待处理动作超时 actionId={} type={} turnId={}",
                action.id(),
                action.type().value(),
                action.turnId());
        return true;
    }

    /**
     * 等待回应的轮次的全部动作都有了结论时，生成下一个恢复轮入队，等待回应的轮次结束为 {@code completed}。还有动作在等待、
     * 轮次已不在等待（例如会话已终止）时什么都不做。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 等待回应的轮次
     * @return 新恢复轮的 ID；没有生成时为空
     */
    public Optional<String> resumeIfComplete(String sessionId, String threadId, String turnId) {
        List<ResolvableAction> all = actions.lockByTurn(turnId);
        Optional<Resumption> planned = ResumptionPlanner.plan(all, claimer.input(turnId));
        if (planned.isEmpty() || !transitions.completeAwaiting(turnId)) {
            return Optional.empty();
        }
        Resumption resumption = planned.get();
        String nextTurnId = Ids.newId(IdPrefix.TURN);
        PlatformContext context = PlatformContexts.current();
        NewTurn next = NewTurn.of(context.tenantId(), context.projectId(), sessionId, threadId, resumption.input());
        if (enqueuer.enqueue(nextTurnId, resumption.interruptRequested() ? next.withInterruptRequested() : next)
                instanceof EnqueueResult.ThreadBusy) {
            // 线程咨询锁下不会发生：等待回应的轮次刚刚结束，同一线程的入队都先取这把锁
            throw new IllegalStateException("生成恢复轮时线程仍有未结束的轮次：" + threadId);
        }
        actions.markConsumed(resumption.consumedActionIds(), nextTurnId);
        log.info(
                "全部待处理动作都有了结论，生成恢复轮 turnId={} nextTurnId={} kind={} interrupt={}",
                turnId,
                nextTurnId,
                resumption.input().kind(),
                resumption.interruptRequested());
        return Optional.of(nextTurnId);
    }

    /**
     * 直接入队一个恢复轮，不经待处理动作：无人值守会话以 {@code PERMISSION_ASKING} 结束时，平台对全部待确认调用自动生成拒绝结果
     * 并立即运行恢复轮（架构设计「待处理动作」中的"无人值守会话"）。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param input 恢复轮的输入
     * @return 新恢复轮的 ID
     */
    public String enqueueResumption(String sessionId, String threadId, TurnInput input) {
        String nextTurnId = Ids.newId(IdPrefix.TURN);
        PlatformContext context = PlatformContexts.current();
        if (enqueuer.enqueue(
                        nextTurnId, NewTurn.of(context.tenantId(), context.projectId(), sessionId, threadId, input))
                instanceof EnqueueResult.ThreadBusy) {
            throw new IllegalStateException("生成恢复轮时线程仍有未结束的轮次：" + threadId);
        }
        return nextTurnId;
    }

    /** "记住此决定"：会话级规则写入 {@code rt.permission_rules}，项目级规则经 {@link PolicyStorePort}。 */
    private void remember(StoredPendingAction action, ActionConclusion conclusion, String decidedBy) {
        Optional<PermissionRule> rule = RememberedRules.of(action.toolName(), conclusion);
        if (rule.isEmpty()) {
            return;
        }
        if (rule.get().layer() == PolicyLayer.SESSION) {
            rules.insert(rule.get(), action.sessionId(), action.id(), decidedBy, clock.instant());
        } else {
            policies.rememberProjectRule(rule.get(), action.id(), decidedBy);
        }
        log.info(
                "记住此决定 actionId={} layer={} tool={} behavior={}",
                action.id(),
                rule.get().layer().value(),
                rule.get().toolName(),
                rule.get().behavior().value());
    }
}
