package cn.refinex.meridian.knowledge.ingestion.infra;

import cn.refinex.meridian.knowledge.ingestion.app.LayoutParser;
import cn.refinex.meridian.knowledge.ingestion.domain.Block;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link LayoutParser} 的实现：调用 doc-parser 的 {@code POST /v1/parse}（ADR-0051），把返回的块转成中间表示。
 *
 * <p>doc-parser 只在集群内网暴露，不校验服务 JWT（ADR-0051）。连接失败、超时与 5xx 可重试；4xx（类型不支持、文件损坏）不可重试。
 *
 * <p>线程安全：创建后不可变。
 */
public class DocParserClient implements LayoutParser {

    static final String PATH = "/v1/parse";

    private final RestClient client;

    /**
     * 创建客户端。
     *
     * @param baseUrl doc-parser 的基础 URL；为空表示没有部署
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时：大的扫描件逐页 OCR 需要数分钟
     */
    public DocParserClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.client = null;
            return;
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public ParsedDocument parse(Path file, String filename, String mediaType) {
        if (client == null) {
            throw IngestionFailure.permanent(
                    IngestionFailure.PARSER_UNAVAILABLE, "该文档需要 doc-parser 解析（扫描件、表格或图片），但没有部署 doc-parser");
        }
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("file", new FileSystemResource(file))
                .filename(filename)
                .contentType(MediaType.parseMediaType(mediaType));
        MultiValueMap<String, ?> body = form.build();
        Result result;
        try {
            result = client.post()
                    .uri(PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Result.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 429) {
                throw IngestionFailure.permanent(
                        IngestionFailure.PARSE_FAILED,
                        "doc-parser 无法解析该文档（" + e.getStatusCode().value() + "）");
            }
            throw IngestionFailure.unavailable(
                    "doc-parser 暂时不可用（" + e.getStatusCode().value() + "）", e);
        } catch (RestClientException e) {
            throw IngestionFailure.unavailable("doc-parser 暂时不可用：" + e.getMessage(), e);
        }
        if (result == null || result.blocks() == null) {
            throw IngestionFailure.unavailable("doc-parser 的响应为空", null);
        }
        return new ParsedDocument(toBlocks(result.blocks()), NAME);
    }

    /** 把 doc-parser 的块转成中间表示；不认识的类型当作段落，不丢文字。 */
    static List<Block> toBlocks(List<WireBlock> wire) {
        List<Block> blocks = new ArrayList<>(wire.size());
        for (WireBlock block : wire) {
            String text = block.text() == null ? "" : block.text();
            switch (block.type() == null ? "paragraph" : block.type()) {
                case "heading" -> blocks.add(new Block.Heading(Math.max(block.level(), 1), text, block.page()));
                case "list_item" -> blocks.add(new Block.ListItem(text, block.page()));
                case "table" ->
                    blocks.add(new Block.Table(block.markdown() == null ? text : block.markdown(), block.page()));
                default -> blocks.add(new Block.Paragraph(text, block.page()));
            }
        }
        return blocks;
    }

    /**
     * {@code POST /v1/parse} 的响应。
     *
     * @param parser 后端：{@code docling} 或 {@code mineru}
     * @param pageCount 页数
     * @param blocks 块
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(
            @JsonProperty("parser") String parser,
            @JsonProperty("page_count") Integer pageCount,
            @JsonProperty("blocks") List<WireBlock> blocks) {}

    /**
     * 一个块。
     *
     * @param type {@code heading}、{@code paragraph}、{@code list_item} 或 {@code table}
     * @param text 文字；表格为表格的纯文字
     * @param markdown 表格的 Markdown；其他类型为空
     * @param level 标题层级，从 1 开始；其他类型为 0
     * @param page 页码；可为空
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireBlock(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text,
            @JsonProperty("markdown") String markdown,
            @JsonProperty("level") int level,
            @JsonProperty("page") Integer page) {}
}
