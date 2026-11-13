package cn.refinex.meridian.controlplane.skill.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ReferencedException;
import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.agent.domain.SpecReference;
import cn.refinex.meridian.controlplane.agent.infra.AgentVersionRefRepository;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.skill.domain.Skill;
import cn.refinex.meridian.controlplane.skill.domain.SkillErrorCode;
import cn.refinex.meridian.controlplane.skill.domain.SkillFile;
import cn.refinex.meridian.controlplane.skill.domain.SkillPackage;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import cn.refinex.meridian.controlplane.skill.infra.SkillRepository;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 技能的管理用例（ADR-0047）：上传技能包作为新技能的版本 1，或作为已有技能的新版本（frontmatter 的 {@code name} 必须与技能名一致）；
 * 文件写进对象存储、清单写进 {@code cp.skill_versions}，版本只追加。删除是软删除：被未删除智能体的已发布版本引用时拒绝，版本与文件保留给
 * 已绑定它们的会话。
 */
@Service
public class SkillService {

    private final SkillRepository skills;
    private final SkillStorage storage;
    private final AgentVersionRefRepository references;
    private final ProjectService projects;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param skills 技能仓储
     * @param storage 技能文件的对象存储
     * @param references 已发布版本的引用，删除前检查
     * @param projects 项目用例，确认项目存在
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public SkillService(
            SkillRepository skills,
            SkillStorage storage,
            AgentVersionRefRepository references,
            ProjectService projects,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.skills = skills;
        this.storage = storage;
        this.references = references;
        this.projects = projects;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 上传技能：技能名取自 frontmatter，带版本 1。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillPackage 校验过的技能包
     * @return 新技能
     */
    public Skill create(String actor, String tenantId, String projectId, SkillPackage skillPackage) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    Skill skill = new Skill(
                            Ids.newId(IdPrefix.SKILL),
                            tenantId,
                            projectId,
                            skillPackage.name(),
                            skillPackage.description(),
                            1,
                            actor,
                            now,
                            now);
                    try {
                        skills.insert(skill);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(
                                SkillErrorCode.NAME_TAKEN, "项目内已有名为 " + skillPackage.name() + " 的技能");
                    }
                    SkillVersion version = store(skill, 1, skillPackage, actor, now);
                    audit.record("skill.created", actor, tenantId, projectId, "skill", skill.id(), details(version));
                    return skill;
                }));
    }

    /**
     * 上传新版本。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillId 技能 ID
     * @param skillPackage 校验过的技能包，frontmatter 的 {@code name} 必须与技能名一致
     * @return 新版本
     */
    public SkillVersion createVersion(
            String actor, String tenantId, String projectId, String skillId, SkillPackage skillPackage) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Skill skill = require(skillId, "UPDATE");
                    if (!skill.name().equals(skillPackage.name())) {
                        throw new ValidationException(
                                CommonErrorCode.VALIDATION_FAILED,
                                "技能包的 name 与技能名不一致",
                                List.of(new FieldViolation(
                                        "files", "SKILL.md 的 frontmatter name 必须是 " + skill.name())));
                    }
                    Instant now = clock.instant();
                    int next = skill.latestVersion() + 1;
                    SkillVersion version = store(skill, next, skillPackage, actor, now);
                    skills.updateLatest(skillId, next, skillPackage.description(), now);
                    audit.record(
                            "skill.version_created", actor, tenantId, projectId, "skill", skillId, details(version));
                    invalidations.resourceChanged(ConfigInvalidations.SKILL, tenantId, skillId);
                    return version;
                }));
    }

    /**
     * 读取技能。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillId 技能 ID
     * @return 技能与引用它的已发布智能体版本
     */
    public SkillView get(String tenantId, String projectId, String skillId) {
        projects.require(tenantId, projectId);
        Skill skill = PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(skillId, null));
        return withReferences(tenantId, List.of(skill)).getFirst();
    }

    /**
     * 列出技能。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param page 分页参数
     * @return 一页技能，各自带引用方
     */
    public PageResult<SkillView> list(String tenantId, String projectId, PageRequest page) {
        projects.require(tenantId, projectId);
        PageResult<Skill> result = PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> PageResult.of(skills.list(page), page, s -> Cursor.of(s.id())));
        return new PageResult<>(withReferences(tenantId, result.data()), result.hasMore(), result.nextCursor());
    }

    /**
     * 列出版本。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillId 技能 ID
     * @param page 分页参数
     * @return 一页版本，按版本号升序
     */
    public PageResult<SkillVersion> versions(String tenantId, String projectId, String skillId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            require(skillId, null);
            return PageResult.of(skills.versions(skillId, page), page, v -> Cursor.of(Integer.toString(v.version())));
        });
    }

    /**
     * 读取一个版本。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillId 技能 ID
     * @param version 版本号
     * @return 版本
     */
    public SkillVersion version(String tenantId, String projectId, String skillId, int version) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            require(skillId, null);
            return skills.version(skillId, version)
                    .orElseThrow(() -> new NotFoundException(
                            SkillErrorCode.VERSION_NOT_FOUND, "技能 " + skillId + " 没有版本 " + version));
        });
    }

    /**
     * 软删除：被未删除智能体的已发布版本引用时以 {@code skill.in_use} 拒绝并列出它们。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param skillId 技能 ID
     */
    public void delete(String actor, String tenantId, String projectId, String skillId) {
        projects.require(tenantId, projectId);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    Skill skill = require(skillId, "UPDATE");
                    List<ResourceReference> referencedBy = PlatformContexts.call(
                            Scopes.tenant(tenantId), () -> references.referencing(SpecReference.Type.SKILL, skillId));
                    if (!referencedBy.isEmpty()) {
                        throw new ReferencedException(
                                SkillErrorCode.IN_USE, "技能 " + skill.name() + " 仍被已发布的智能体版本引用", referencedBy);
                    }
                    skills.softDelete(skillId, clock.instant());
                    audit.record(
                            "skill.deleted",
                            actor,
                            tenantId,
                            projectId,
                            "skill",
                            skillId,
                            Map.of("name", skill.name()));
                    invalidations.resourceChanged(ConfigInvalidations.SKILL, tenantId, skillId);
                }));
    }

    private SkillVersion store(Skill skill, int version, SkillPackage skillPackage, String actor, Instant now) {
        String root = SkillVersion.objectRoot(skill.tenantId(), skill.projectId(), skill.id(), version);
        List<SkillFile> manifest = storage.write(root, skillPackage.files());
        SkillVersion stored = new SkillVersion(
                skill.id(),
                version,
                skill.tenantId(),
                skill.projectId(),
                skillPackage.name(),
                skillPackage.description(),
                manifest,
                root,
                actor,
                now);
        skills.insertVersion(stored);
        return stored;
    }

    /** 引用方在租户级的 {@code agent_version_refs} 中，按租户作用域一次查全。 */
    private List<SkillView> withReferences(String tenantId, List<Skill> page) {
        Map<String, List<ResourceReference>> refs = PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> references.referencingAll(
                        SpecReference.Type.SKILL, page.stream().map(Skill::id).toList()));
        return page.stream()
                .map(skill -> new SkillView(skill, refs.getOrDefault(skill.id(), List.of())))
                .toList();
    }

    private Skill require(String skillId, String lock) {
        return skills.find(skillId, lock)
                .orElseThrow(() -> new NotFoundException(SkillErrorCode.NOT_FOUND, "技能不存在：" + skillId));
    }

    private static Map<String, Object> details(SkillVersion version) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", version.name());
        details.put("version", version.version());
        details.put("files", version.files().size());
        return details;
    }

    /**
     * 技能与引用它的已发布智能体版本（ADR-0056）。
     *
     * @param skill 技能
     * @param references 引用方，按版本 ID 排序
     */
    public record SkillView(Skill skill, List<ResourceReference> references) {}
}
