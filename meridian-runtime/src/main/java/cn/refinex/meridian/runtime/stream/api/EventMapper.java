package cn.refinex.meridian.runtime.stream.api;

import cn.refinex.meridian.api.model.ContentBlock;
import cn.refinex.meridian.api.model.EventPage;
import cn.refinex.meridian.api.model.FileBlock;
import cn.refinex.meridian.api.model.ImageBlock;
import cn.refinex.meridian.api.model.RecoveryDecision;
import cn.refinex.meridian.api.model.RememberScope;
import cn.refinex.meridian.api.model.SendEventsResponse;
import cn.refinex.meridian.api.model.SessionEvent;
import cn.refinex.meridian.api.model.TextBlock;
import cn.refinex.meridian.api.model.UserCustomToolResultInput;
import cn.refinex.meridian.api.model.UserEventInput;
import cn.refinex.meridian.api.model.UserInterruptInput;
import cn.refinex.meridian.api.model.UserMessageInput;
import cn.refinex.meridian.api.model.UserRecoveryConfirmationInput;
import cn.refinex.meridian.api.model.UserToolConfirmationInput;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.stream.app.EventEnvelopes;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 事件的 DTO 映射。请求中的用户事件转为 engine-spi 的 {@link UserEvent}；存储的事件按信封 JSON 解析为响应 DTO，
 * 存储格式即 API 格式（ADR-0027），所以响应与 Redis Stream、SSE 中的信封逐字段相同。
 */
final class EventMapper {

    private EventMapper() {}

    static List<UserEvent> toEvents(List<UserEventInput> inputs) {
        List<UserEvent> events = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            events.add(toEvent(inputs.get(i), "events[" + i + "]"));
        }
        return events;
    }

    static SendEventsResponse toResponse(List<StoredEvent> events) {
        return new SendEventsResponse()
                .data(events.stream().map(EventMapper::toDto).toList());
    }

    static EventPage toPage(PageResult<StoredEvent> page) {
        return new EventPage()
                .data(page.data().stream().map(EventMapper::toDto).toList())
                .hasMore(page.hasMore())
                .nextCursor(page.nextCursor());
    }

    static SessionEvent toDto(StoredEvent event) {
        return JsonMappers.lenient().readValue(EventEnvelopes.json(event), SessionEvent.class);
    }

    private static UserEvent toEvent(UserEventInput input, String field) {
        return switch (input) {
            case UserMessageInput message ->
                new UserEvent.Message(
                        toContent(message.getContent(), field + ".content"),
                        message.getDelivery() == null
                                ? UserEvent.Delivery.QUEUE
                                : UserEvent.Delivery.valueOf(
                                        message.getDelivery().name()));
            case UserInterruptInput _ -> new UserEvent.Interrupt();
            case UserToolConfirmationInput confirmation ->
                new UserEvent.ToolConfirmation(
                        confirmation.getActionId(),
                        Boolean.TRUE.equals(confirmation.getApproved()),
                        confirmation.getUpdatedInput(),
                        confirmation.getDenyReason(),
                        confirmation.getRememberScope() == null
                                ? UserEvent.RememberScope.NONE
                                : rememberScope(confirmation.getRememberScope()));
            case UserCustomToolResultInput result ->
                new UserEvent.CustomToolResult(
                        result.getActionId(),
                        result.getContent() == null ? List.of() : toContent(result.getContent(), field + ".content"),
                        Boolean.TRUE.equals(result.getError()));
            case UserRecoveryConfirmationInput recovery ->
                new UserEvent.RecoveryConfirmation(
                        recovery.getActionId(),
                        recovery.getDecision() == RecoveryDecision.RETRY
                                ? UserEvent.RecoveryDecision.RETRY
                                : UserEvent.RecoveryDecision.SKIP);
        };
    }

    private static UserEvent.RememberScope rememberScope(RememberScope scope) {
        return switch (scope) {
            case NONE -> UserEvent.RememberScope.NONE;
            case SESSION -> UserEvent.RememberScope.SESSION;
            case PROJECT -> UserEvent.RememberScope.PROJECT;
        };
    }

    private static List<MessageContent> toContent(List<ContentBlock> blocks, String field) {
        List<MessageContent> content = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            content.add(toContent(blocks.get(i), field + "[" + i + "]"));
        }
        return content;
    }

    private static MessageContent toContent(ContentBlock block, String field) {
        return switch (block) {
            case TextBlock text -> new MessageContent.Text(text.getText());
            case ImageBlock image -> {
                if ((image.getUrl() == null) == (image.getFileId() == null)) {
                    throw new ValidationException(
                            CommonErrorCode.VALIDATION_FAILED,
                            "图片块的 url 与 file_id 必须恰好给出一个",
                            List.of(new FieldViolation(field, "url 与 file_id 必须恰好给出一个")));
                }
                if (image.getUrl() != null && !isHttpUrl(image.getUrl())) {
                    throw new ValidationException(
                            CommonErrorCode.VALIDATION_FAILED,
                            "图片块的 url 只能是 http 或 https 地址",
                            List.of(new FieldViolation(field + ".url", "只能是带主机的 http 或 https 地址")));
                }
                yield new MessageContent.Image(image.getUrl(), image.getFileId(), image.getMediaType());
            }
            case FileBlock file ->
                new MessageContent.FileRef(file.getFileId(), file.getFilename(), file.getMediaType());
        };
    }

    /**
     * 图片地址只能是带主机的 http、https 绝对地址（ADR-0045 第 6 条，登记事项 D52）：AgentScope 的部分模型转换器把其他写法当作本机
     * 路径读取。
     */
    private static boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https"))
                    && uri.getRawAuthority() != null
                    && !uri.getRawAuthority().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
