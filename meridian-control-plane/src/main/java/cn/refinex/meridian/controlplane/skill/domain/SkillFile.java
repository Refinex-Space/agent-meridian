package cn.refinex.meridian.controlplane.skill.domain;

/**
 * 技能版本中的一个文件（清单项）。
 *
 * @param path 相对路径
 * @param size 字节数
 * @param sha256 内容的 SHA-256（十六进制）
 */
public record SkillFile(String path, long size, String sha256) {}
