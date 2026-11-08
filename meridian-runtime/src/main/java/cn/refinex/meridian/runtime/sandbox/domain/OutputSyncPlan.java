package cn.refinex.meridian.runtime.sandbox.domain;

import cn.refinex.meridian.engine.spi.driver.SandboxDriver.OutputFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 产物同步的比对（架构设计「会话沙箱」"产物"）：沙箱中 {@code outputs/} 的清单与上次同步的清单按路径与 SHA-256 比较，得出要上传、
 * 要删除与因过大跳过的文件。同步是镜像：沙箱里删掉的文件，副本也删掉；变得过大的文件不再保留旧副本。
 *
 * @param uploads 新增或内容变化、且不超过上限的文件
 * @param deletions 要删除副本的路径：沙箱里已没有，或超过了上限
 * @param skipped 超过上限、不同步的文件
 */
public record OutputSyncPlan(List<OutputFile> uploads, List<String> deletions, List<OutputFile> skipped) {

    /**
     * 比对。
     *
     * @param listing 沙箱中的产物清单
     * @param synced 上次同步的清单：路径 → SHA-256
     * @param maxFileSize 单个文件的上限，单位字节
     * @return 同步计划，上传与删除按路径排列
     */
    public static OutputSyncPlan of(Collection<OutputFile> listing, Map<String, String> synced, long maxFileSize) {
        List<OutputFile> uploads = new ArrayList<>();
        List<OutputFile> skipped = new ArrayList<>();
        Map<String, OutputFile> byPath =
                listing.stream().collect(Collectors.toMap(OutputFile::path, Function.identity(), (a, b) -> a));
        for (OutputFile file : byPath.values()) {
            if (file.size() > maxFileSize) {
                skipped.add(file);
            } else if (!file.sha256().equals(synced.get(file.path()))) {
                uploads.add(file);
            }
        }
        Set<String> keep = byPath.values().stream()
                .filter(file -> file.size() <= maxFileSize)
                .map(OutputFile::path)
                .collect(Collectors.toSet());
        List<String> deletions = synced.keySet().stream()
                .filter(path -> !keep.contains(path))
                .sorted()
                .toList();
        uploads.sort((a, b) -> a.path().compareTo(b.path()));
        skipped.sort((a, b) -> a.path().compareTo(b.path()));
        return new OutputSyncPlan(List.copyOf(uploads), deletions, List.copyOf(skipped));
    }

    /**
     * 是否什么都不用做。
     *
     * @return 没有上传与删除时为 true
     */
    public boolean isEmpty() {
        return uploads.isEmpty() && deletions.isEmpty();
    }
}
