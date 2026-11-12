package cn.refinex.meridian.runtime.file.app;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.file.domain.FileNames;
import cn.refinex.meridian.runtime.file.domain.FileReferences;
import cn.refinex.meridian.runtime.file.domain.StoredFile;
import cn.refinex.meridian.runtime.file.infra.FileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 发送事件时校验并补全对平台文件的引用（ADR-0050）：{@code user.message} 与 {@code user.custom_tool_result} 中按文件 ID 引用的文件必须
 * 属于当前项目；按文件 ID 引用的图片，检测类型必须是图片。保存的事件中 {@code filename} 是规范化后的名字（未给出时取文件的名字），
 * {@code media_type} 是服务端检测的类型，客户端声明的类型不采用。
 *
 * <p>在当前项目内执行，方法会阻塞；线程安全：无状态。
 */
@Service
public class FileReferenceResolver {

    private final FileRepository files;

    /**
     * 创建解析器。
     *
     * @param files 文件仓储
     */
    public FileReferenceResolver(FileRepository files) {
        this.files = files;
    }

    /**
     * 校验并补全一批事件中的文件引用。
     *
     * @param events 用户事件，按请求顺序
     * @return 补全后的事件，顺序与数量不变；没有文件引用的事件原样返回
     * @throws ValidationException 引用的文件不存在、不属于当前项目，或图片块引用的不是图片时；字段为
     *     {@code events[i].content[j].file_id}
     */
    public List<UserEvent> resolve(List<UserEvent> events) {
        List<String> ids = events.stream()
                .flatMap(event -> FileReferences.of(contentOf(event)).stream())
                .map(FileReferences.Reference::fileId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return events;
        }
        Map<String, StoredFile> stored =
                files.findAll(ids).stream().collect(Collectors.toMap(StoredFile::id, Function.identity()));
        List<FieldViolation> violations = new ArrayList<>();
        List<UserEvent> resolved = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            UserEvent event = events.get(i);
            String field = "events[" + i + "].content";
            resolved.add(
                    switch (event) {
                        case UserEvent.Message message ->
                            new UserEvent.Message(
                                    resolve(message.content(), stored, field, violations), message.delivery());
                        case UserEvent.CustomToolResult result ->
                            new UserEvent.CustomToolResult(
                                    result.actionId(),
                                    resolve(result.content(), stored, field, violations),
                                    result.error());
                        default -> event;
                    });
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "引用的文件不可用", violations);
        }
        return resolved;
    }

    private static List<MessageContent> resolve(
            List<MessageContent> content,
            Map<String, StoredFile> stored,
            String field,
            List<FieldViolation> violations) {
        List<MessageContent> resolved = new ArrayList<>(content.size());
        for (int j = 0; j < content.size(); j++) {
            MessageContent block = content.get(j);
            String where = field + "[" + j + "].file_id";
            switch (block) {
                case MessageContent.FileRef ref -> {
                    StoredFile file = stored.get(ref.fileId());
                    if (file == null) {
                        violations.add(new FieldViolation(where, "文件不存在或不属于当前项目：" + ref.fileId()));
                        resolved.add(block);
                    } else {
                        String filename =
                                ref.filename() == null ? file.filename() : FileNames.normalize(ref.filename());
                        resolved.add(new MessageContent.FileRef(file.id(), filename, file.mediaType()));
                    }
                }
                case MessageContent.Image image
                when image.fileId() != null -> {
                    StoredFile file = stored.get(image.fileId());
                    if (file == null) {
                        violations.add(new FieldViolation(where, "文件不存在或不属于当前项目：" + image.fileId()));
                        resolved.add(block);
                    } else if (!file.isImage()) {
                        violations.add(new FieldViolation(where, "文件不是图片（检测类型为 " + file.mediaType() + "），请用 file 块"));
                        resolved.add(block);
                    } else {
                        resolved.add(new MessageContent.Image(null, file.id(), file.mediaType()));
                    }
                }
                default -> resolved.add(block);
            }
        }
        return resolved;
    }

    private static List<MessageContent> contentOf(UserEvent event) {
        return switch (event) {
            case UserEvent.Message message -> message.content();
            case UserEvent.CustomToolResult result -> result.content();
            default -> List.of();
        };
    }
}
