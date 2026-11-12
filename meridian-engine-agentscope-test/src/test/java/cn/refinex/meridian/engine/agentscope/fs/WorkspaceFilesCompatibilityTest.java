package cn.refinex.meridian.engine.agentscope.fs;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.port.WorkspaceFiles;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * runtime 写入、读取 {@code none} 档工作区用的 {@link WorkspaceFiles} 与 AgentScope 2.0.4 {@code RemoteFilesystem} 的
 * {@code FileData} 格式互通（ADR-0050）：放置的上传文件智能体读得到，智能体写的文件工作区 API 读得到。
 */
class WorkspaceFilesCompatibilityTest {

    private static final List<String> NAMESPACE = WorkspaceFiles.namespace("ten_1", "prj_1", null, "ses_1");

    private final InMemoryStore store = new InMemoryStore();
    private final RemoteFilesystem filesystem = new RemoteFilesystem(store, NAMESPACE);

    @Test
    void namespaceUsesThePlaceholderWithoutEndUser() {
        assertThat(NAMESPACE).containsExactly("ten_1", "prj_1", WorkspaceNamespaces.NO_END_USER, "ses_1");
        assertThat(WorkspaceFiles.namespace("ten_1", "prj_1", "u_1", "ses_1"))
                .containsExactly("ten_1", "prj_1", "u_1", "ses_1");
    }

    @Test
    void filesEncodedByThePlatformAreReadByTheFilesystem() {
        byte[] text = "第一行\nsecond line".getBytes(StandardCharsets.UTF_8);
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff, 1};
        Instant now = Instant.parse("2026-11-12T08:00:00Z");
        store.put(NAMESPACE, WorkspaceFiles.key("uploads/fil_1/notes.md"), WorkspaceFiles.encode(text, now));
        store.put(NAMESPACE, WorkspaceFiles.key("uploads/fil_2/a.png"), WorkspaceFiles.encode(binary, now));

        assertThat(filesystem
                        .read(null, "/uploads/fil_1/notes.md", 0, 0)
                        .fileData()
                        .content())
                .isEqualTo("第一行\nsecond line");
        List<FileDownloadResponse> downloaded =
                filesystem.downloadFiles(null, List.of("/uploads/fil_1/notes.md", "/uploads/fil_2/a.png"));
        assertThat(downloaded.get(0).content()).isEqualTo(text);
        assertThat(downloaded.get(1).content()).isEqualTo(binary);
        assertThat(filesystem.exists(null, "/uploads/fil_2/a.png")).isTrue();
    }

    @Test
    void filesWrittenByTheFilesystemAreDecodedByThePlatform() {
        byte[] binary = {0, 1, 2, (byte) 0xfe};
        filesystem.write(null, "/reports/q3.md", "# Q3\n收入");
        filesystem.uploadFiles(null, List.of(Map.entry("/data/raw.bin", binary)));

        StoreItem text = store.get(NAMESPACE, "/reports/q3.md");
        StoreItem raw = store.get(NAMESPACE, "/data/raw.bin");
        assertThat(WorkspaceFiles.decode(text.value()))
                .hasValueSatisfying(bytes ->
                        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("# Q3\n收入"));
        assertThat(WorkspaceFiles.decode(raw.value())).hasValue(binary);
        assertThat(WorkspaceFiles.contentSize(text.value()))
                .hasValue((long) "# Q3\n收入".getBytes(StandardCharsets.UTF_8).length);
        assertThat(WorkspaceFiles.contentSize(raw.value())).hasValue(4L);
        assertThat(WorkspaceFiles.relativePath(text.key())).isEqualTo("reports/q3.md");
    }

    @Test
    void valuesThatAreNotFilesHaveNoContent() {
        assertThat(WorkspaceFiles.decode(Map.of("other", 1))).isEmpty();
        assertThat(WorkspaceFiles.contentSize(Map.of("other", 1))).isEmpty();
        assertThat(WorkspaceFiles.decode(Map.of("content", List.of("a", "b"))))
                .hasValue("a\nb".getBytes(StandardCharsets.UTF_8));
    }
}
