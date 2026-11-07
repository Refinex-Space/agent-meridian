package cn.refinex.meridian.runtime.session.api;

import cn.refinex.meridian.api.model.CreateSessionRequest;
import cn.refinex.meridian.api.model.CustomToolDeclaration;
import cn.refinex.meridian.api.model.EndUserAttributes;
import cn.refinex.meridian.api.model.Interaction;
import cn.refinex.meridian.api.model.SessionPage;
import cn.refinex.meridian.api.model.SessionStatus;
import cn.refinex.meridian.api.model.SessionThread;
import cn.refinex.meridian.api.model.SideEffect;
import cn.refinex.meridian.api.model.ThreadPage;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.session.app.CreateSessionCommand;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.web.ApiTimes;
import java.util.List;
import java.util.Locale;

/** 会话与线程的 DTO 映射：请求 DTO → 用例参数，领域对象 → 响应 DTO（规范 {@code meridian-v1.yaml}）。 */
final class SessionMapper {

    private SessionMapper() {}

    static CreateSessionCommand toCommand(CreateSessionRequest request) {
        return new CreateSessionCommand(
                request.getAgent(),
                request.getEnvironment(),
                request.getEndUserId(),
                request.getEndUserAttributes() == null
                        ? null
                        : new cn.refinex.meridian.runtime.session.domain.EndUserAttributes(
                                request.getEndUserAttributes().getAclTags()),
                request.getInteraction() == Interaction.UNATTENDED
                        ? cn.refinex.meridian.common.context.Interaction.UNATTENDED
                        : cn.refinex.meridian.common.context.Interaction.ATTENDED,
                request.getMetadata(),
                request.getCustomTools() == null
                        ? List.of()
                        : request.getCustomTools().stream()
                                .map(SessionMapper::toDeclaration)
                                .toList());
    }

    static cn.refinex.meridian.api.model.Session toDto(Session session) {
        return new cn.refinex.meridian.api.model.Session()
                .id(session.id())
                .agentVersionId(session.agentVersionId())
                .environmentId(session.environmentId())
                .environmentRevision(session.environmentRevision())
                .endUserId(session.endUserId())
                .endUserAttributes(new EndUserAttributes()
                        .aclTags(session.endUserAttributes().aclTags()))
                .interaction(Interaction.fromValue(session.interaction().name().toLowerCase(Locale.ROOT)))
                .status(SessionStatus.fromValue(session.status().value()))
                .metadata(session.metadata())
                .customTools(
                        session.customTools().stream().map(SessionMapper::toDto).toList())
                .lastSeq(session.lastSeq())
                .createdAt(ApiTimes.utc(session.createdAt()))
                .updatedAt(ApiTimes.utc(session.updatedAt()))
                .terminatedAt(ApiTimes.utc(session.terminatedAt()))
                .deletedAt(ApiTimes.utc(session.deletedAt()));
    }

    static SessionPage toPage(PageResult<Session> page) {
        return new SessionPage()
                .data(page.data().stream().map(SessionMapper::toDto).toList())
                .hasMore(page.hasMore())
                .nextCursor(page.nextCursor());
    }

    static ThreadPage toThreadPage(PageResult<cn.refinex.meridian.runtime.session.domain.SessionThread> page) {
        return new ThreadPage()
                .data(page.data().stream().map(SessionMapper::toDto).toList())
                .hasMore(page.hasMore())
                .nextCursor(page.nextCursor());
    }

    static SessionThread toDto(cn.refinex.meridian.runtime.session.domain.SessionThread thread) {
        return new SessionThread()
                .id(thread.id())
                .sessionId(thread.sessionId())
                .parentThreadId(thread.parentThreadId())
                .parentToolUseId(thread.parentToolUseId())
                .agentName(thread.agentName())
                .status(SessionStatus.fromValue(thread.status().value()))
                .createdAt(ApiTimes.utc(thread.createdAt()));
    }

    private static cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration toDeclaration(CustomToolDeclaration tool) {
        SideEffect sideEffect = tool.getSideEffect() == null ? SideEffect.NON_IDEMPOTENT : tool.getSideEffect();
        return new cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration(
                tool.getName(),
                tool.getDescription(),
                tool.getInputSchema(),
                cn.refinex.meridian.engine.spi.spec.SideEffect.valueOf(sideEffect.name()));
    }

    private static CustomToolDeclaration toDto(cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration tool) {
        return new CustomToolDeclaration()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .sideEffect(SideEffect.fromValue(tool.sideEffect().value()));
    }
}
