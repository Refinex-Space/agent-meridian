/**
 * 技能（架构设计「引擎装配」中的 {@code skillRepository}，ADR-0047）：上传技能包（{@code SKILL.md} 与附带文件），校验 frontmatter，文件存
 * 对象存储，按版本只追加；runtime 经内部接口读取版本内容，装配时生成受保护路径 {@code skills/}。项目级资源。
 */
package cn.refinex.meridian.controlplane.skill;
