package cn.refinex.meridian.controlplane.skill.app;

import cn.refinex.meridian.controlplane.skill.domain.SkillFile;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 技能文件的对象存储（ADR-0047）：每个文件一个对象，键为版本的前缀加相对路径；版本不可变，同一键不会被改写。
 *
 * <p>线程安全：无状态。
 */
@Component
public class SkillStorage {

    private final ObjectStore objects;

    /**
     * 创建存储。
     *
     * @param objects 对象存储
     */
    public SkillStorage(ObjectStore objects) {
        this.objects = objects;
    }

    /**
     * 写入一个版本的全部文件。
     *
     * @param objectRoot 版本的键前缀
     * @param files 相对路径 → 内容
     * @return 文件清单，按路径排序
     */
    public List<SkillFile> write(String objectRoot, SortedMap<String, String> files) {
        List<SkillFile> manifest = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
            objects.put(objectRoot + file.getKey(), bytes, contentType(file.getKey()));
            manifest.add(new SkillFile(file.getKey(), bytes.length, sha256(bytes)));
        }
        return List.copyOf(manifest);
    }

    /**
     * 读出一个版本的全部文件。
     *
     * @param version 版本
     * @return 相对路径 → 内容，按路径排序
     * @throws IllegalStateException 清单中的文件在对象存储中不存在时
     */
    public SortedMap<String, String> read(SkillVersion version) {
        SortedMap<String, String> files = new TreeMap<>();
        for (SkillFile file : version.files()) {
            byte[] bytes = objects.get(version.objectRoot() + file.path())
                    .orElseThrow(() -> new IllegalStateException(
                            "技能文件不在对象存储中：" + version.skillId() + " v" + version.version() + " " + file.path()));
            files.put(file.path(), new String(bytes, StandardCharsets.UTF_8));
        }
        return files;
    }

    private static String contentType(String path) {
        return path.endsWith(".md") ? "text/markdown; charset=utf-8" : "text/plain; charset=utf-8";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
