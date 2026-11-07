package cn.refinex.meridian.engine.spi.engine.input;

import java.util.Objects;

/**
 * 消息中的一个内容块：文本、图片或文件引用。用户消息、自定义工具结果、智能体输出的消息共用这套类型，
 * 对应 API 中 {@code content} 数组的元素（架构设计「事件」）。
 *
 * <p>二进制内容不内联：图片与文件以 URL 或平台文件 ID（{@code fil_…}，经 {@code /v1/files} 上传）引用，
 * 由内核在翻译为 AgentScope 内容块时解析。
 */
public sealed interface MessageContent {

    /**
     * 文本块。
     *
     * @param text 文本内容；必填，可为空字符串
     */
    record Text(String text) implements MessageContent {

        /**
         * 创建文本块。
         *
         * @throws NullPointerException 文本为空引用时
         */
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * 图片块，{@code url} 与 {@code fileId} 恰好有一个。
     *
     * @param url 图片的 http 或 https 地址；可为空，表示用 {@code fileId}
     * @param fileId 平台文件 ID（{@code fil_…}）；可为空，表示用 {@code url}
     * @param mediaType 媒体类型，例如 {@code image/png}；可为空，表示由来源推断
     */
    record Image(String url, String fileId, String mediaType) implements MessageContent {

        /**
         * 创建图片块。
         *
         * @throws IllegalArgumentException {@code url} 与 {@code fileId} 不是恰好有一个时
         */
        public Image {
            if ((url == null) == (fileId == null)) {
                throw new IllegalArgumentException("图片块的 url 与 fileId 必须恰好有一个");
            }
        }
    }

    /**
     * 文件引用块。
     *
     * @param fileId 平台文件 ID（{@code fil_…}）；必填
     * @param filename 文件名，给模型看的名字；可为空，表示用文件元数据中的名字
     * @param mediaType 媒体类型，例如 {@code application/pdf}；可为空，表示用文件元数据中的类型
     */
    record FileRef(String fileId, String filename, String mediaType) implements MessageContent {

        /**
         * 创建文件引用块。
         *
         * @throws NullPointerException 缺少文件 ID 时
         */
        public FileRef {
            Objects.requireNonNull(fileId, "fileId");
        }
    }
}
