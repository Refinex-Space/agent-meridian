package cn.refinex.meridian.runtime.file.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * 媒体类型的服务端检测（ADR-0050）：Apache Tika 按内容的特征字节判断，文件名只作提示（内容判断不出时才看扩展名）；客户端声明的类型一律
 * 不采用。上传文件与工作区文件的读取共用。
 *
 * <p>线程安全：{@link Tika} 的检测器无状态，可以共享。
 */
@Component
public class MediaTypes {

    private final Tika tika = new Tika();

    /**
     * 检测文件的媒体类型。
     *
     * @param file 本机文件
     * @param filename 规范化后的文件名，作为提示
     * @return 媒体类型，例如 {@code application/pdf}；无法判断时为 {@code application/octet-stream}
     */
    public String detect(Path file, String filename) {
        try (InputStream in = Files.newInputStream(file)) {
            return tika.detect(in, filename);
        } catch (IOException e) {
            throw new UncheckedIOException("检测媒体类型失败", e);
        }
    }

    /**
     * 检测内容的媒体类型。
     *
     * @param content 内容
     * @param filename 文件名或路径的最后一段，作为提示
     * @return 媒体类型；无法判断时为 {@code application/octet-stream}
     */
    public String detect(byte[] content, String filename) {
        return tika.detect(content, filename);
    }
}
