package cn.refinex.meridian.engine.spi.port;

import java.net.URI;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 抓取网页，供平台工具 {@code web_fetch} 使用，由 runtime 的 {@code tooling} 实现，见架构设计「端口」「平台工具」。
 *
 * <p>实现必须做 SSRF 防护：拒绝内网、回环、链路本地地址；DNS 解析后再校验一次；响应体上限 5 MB。
 */
public interface WebFetchPort {

    /** 响应体上限，单位字节。架构设计「平台工具」规定 5 MB。 */
    long MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    /**
     * 抓取一个 URL，并把 HTML 转为便于模型阅读的文本。
     *
     * @param url 目标地址，只支持 http 与 https
     * @return 页面内容；地址被 SSRF 防护拒绝时以 {@code ForbiddenException} 失败
     */
    Mono<Page> fetch(URI url);

    /**
     * 抓取到的页面。
     *
     * @param url 请求的地址；必填
     * @param finalUrl 跟随重定向后的地址；必填
     * @param contentType 响应的媒体类型；必填
     * @param content 转换后的文本；必填
     * @param truncated 响应体是否因超过上限被截断
     */
    record Page(URI url, URI finalUrl, String contentType, String content, boolean truncated) {

        /**
         * 创建页面。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Page {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(finalUrl, "finalUrl");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(content, "content");
        }
    }
}
