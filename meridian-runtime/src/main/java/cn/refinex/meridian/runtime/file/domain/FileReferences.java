package cn.refinex.meridian.runtime.file.domain;

import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内容块中对平台文件的引用（{@code file} 块与按 {@code file_id} 引用的 {@code image} 块，ADR-0050）。
 *
 * <p>纯函数，线程安全。
 */
public final class FileReferences {

    private FileReferences() {}

    /**
     * 一个引用。
     *
     * @param fileId 文件 ID；必填
     * @param filename 块中给出的文件名；可为空（图片块总为空）
     * @param image 是否为图片块
     */
    public record Reference(String fileId, String filename, boolean image) {

        /**
         * 创建引用。
         *
         * @throws NullPointerException 缺少文件 ID 时
         */
        public Reference {
            Objects.requireNonNull(fileId, "fileId");
        }
    }

    /**
     * 内容块中的引用，按出现顺序。
     *
     * @param content 内容块
     * @return 引用；没有时为空列表
     */
    public static List<Reference> of(List<MessageContent> content) {
        List<Reference> references = new ArrayList<>();
        for (MessageContent block : content) {
            switch (block) {
                case MessageContent.FileRef file ->
                    references.add(new Reference(file.fileId(), file.filename(), false));
                case MessageContent.Image image
                when image.fileId() != null -> references.add(new Reference(image.fileId(), null, true));
                default -> {}
            }
        }
        return references;
    }

    /**
     * 一轮输入中的引用：新消息与自定义工具结果中的内容块；确认轮与只有恢复说明的轮次没有。
     *
     * @param input 一轮的输入
     * @return 引用，按出现顺序
     */
    public static List<Reference> of(TurnInput input) {
        List<Reference> references = new ArrayList<>();
        switch (input) {
            case TurnInput.NewMessages messages ->
                messages.messages().forEach(message -> references.addAll(of(message.content())));
            case TurnInput.CustomToolResults results ->
                results.results().forEach(result -> references.addAll(of(result.content())));
            case TurnInput.Confirmations _, TurnInput.RecoveryOnly _ -> {}
        }
        return references;
    }
}
