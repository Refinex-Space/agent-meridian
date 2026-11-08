package cn.refinex.meridian.engine.agentscope.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

/** {@link SkillProjection}：投影的文件、与 {@code sha256sum} 管道一致的摘要、tar 归档。 */
class SkillProjectionTest {

    static final SkillDefinition DOCX = new SkillDefinition(
            "skl_docx",
            1,
            "docx",
            Map.of("SKILL.md", "---\nname: docx\n---\n", "scripts/md_to_docx.py", "print('hi')\n", "README", "r"));
    static final SkillDefinition CHART =
            new SkillDefinition("skl_chart", 2, "chart", Map.of("SKILL.md", "---\nname: chart\n---\n", "plot.py", "x"));

    @Test
    void skillBodiesAreNotProjectedAndPathsAreInByteOrder() {
        Map<String, byte[]> files = SkillProjection.files(List.of(DOCX, CHART));

        assertThat(files.keySet()).containsExactly("chart/plot.py", "docx/README", "docx/scripts/md_to_docx.py");
    }

    @Test
    void theDigestHashesTheSha256sumListingOfTheFiles() throws Exception {
        Map<String, byte[]> files = SkillProjection.files(List.of(CHART));
        String listing = sha256("x".getBytes(StandardCharsets.UTF_8)) + "  ./chart/plot.py\n";

        assertThat(SkillProjection.digest(files)).isEqualTo(sha256(listing.getBytes(StandardCharsets.UTF_8)));
        assertThat(SkillProjection.digest(Map.of())).as("没有技能时期望目录不存在").isEmpty();
    }

    @Test
    void theArchiveHoldsEveryFileUnderTheSkillsDirectoryWithoutARootEntry() throws Exception {
        Map<String, byte[]> files = SkillProjection.files(List.of(DOCX));

        Map<String, String> entries = new LinkedHashMap<>();
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(SkillProjection.archive(files)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertThat(entries)
                .containsExactly(
                        Map.entry(".meridian/skills/docx/README", "r"),
                        Map.entry(".meridian/skills/docx/scripts/md_to_docx.py", "print('hi')\n"));
    }

    static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
