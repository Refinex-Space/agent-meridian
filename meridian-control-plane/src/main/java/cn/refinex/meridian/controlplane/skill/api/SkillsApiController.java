package cn.refinex.meridian.controlplane.skill.api;

import cn.refinex.meridian.api.admin.model.Skill;
import cn.refinex.meridian.api.admin.model.SkillFile;
import cn.refinex.meridian.api.admin.model.SkillFileContent;
import cn.refinex.meridian.api.admin.model.SkillPage;
import cn.refinex.meridian.api.admin.model.SkillVersion;
import cn.refinex.meridian.api.admin.model.SkillVersionPage;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.openapi.admin.SkillsApi;
import cn.refinex.meridian.controlplane.skill.app.SkillService;
import cn.refinex.meridian.controlplane.skill.domain.SkillPackage;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 技能的管理接口（Skills 标签，ADR-0047）：只做授权声明、decode → call → map。 */
@RestController
public class SkillsApiController implements SkillsApi {

    private final SkillService skills;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param skills 技能用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public SkillsApiController(SkillService skills, AdminRequests requests, HttpServletRequest request) {
        this.skills = skills;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Skill> createSkill(
            String tenantId,
            String projectId,
            cn.refinex.meridian.api.admin.model.SkillPackage body,
            String idempotencyKey) {
        SkillPackage skillPackage = parse(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Skill.class,
                () -> toDto(skills.create(actor(), tenantId, projectId, skillPackage)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<SkillPage> listSkills(String tenantId, String projectId, Integer limit, String after) {
        var page = skills.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new SkillPage(
                page.data().stream().map(SkillsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<Skill> getSkill(String tenantId, String projectId, String skillId) {
        return ResponseEntity.ok(toDto(skills.get(tenantId, projectId, skillId)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Void> deleteSkill(String tenantId, String projectId, String skillId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> skills.delete(actor(), tenantId, projectId, skillId));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<SkillVersion> createSkillVersion(
            String tenantId,
            String projectId,
            String skillId,
            cn.refinex.meridian.api.admin.model.SkillPackage body,
            String idempotencyKey) {
        SkillPackage skillPackage = parse(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                SkillVersion.class,
                () -> toDto(skills.createVersion(actor(), tenantId, projectId, skillId, skillPackage)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<SkillVersionPage> listSkillVersions(
            String tenantId, String projectId, String skillId, Integer limit, String after) {
        var page = skills.versions(tenantId, projectId, skillId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new SkillVersionPage(
                page.data().stream().map(SkillsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<SkillVersion> getSkillVersion(
            String tenantId, String projectId, String skillId, Integer version) {
        return ResponseEntity.ok(toDto(skills.version(tenantId, projectId, skillId, version)));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static SkillPackage parse(cn.refinex.meridian.api.admin.model.SkillPackage body) {
        List<Map.Entry<String, String>> files = body.getFiles().stream()
                .map(file -> Map.entry(file.getPath(), contentOf(file)))
                .toList();
        return SkillPackage.parse(files);
    }

    private static String contentOf(SkillFileContent file) {
        return file.getContent() == null ? "" : file.getContent();
    }

    private static Skill toDto(cn.refinex.meridian.controlplane.skill.domain.Skill skill) {
        return new Skill()
                .id(skill.id())
                .tenantId(skill.tenantId())
                .projectId(skill.projectId())
                .name(skill.name())
                .description(skill.description())
                .latestVersion(skill.latestVersion())
                .createdAt(Times.utc(skill.createdAt()))
                .updatedAt(Times.utc(skill.updatedAt()));
    }

    private static SkillVersion toDto(cn.refinex.meridian.controlplane.skill.domain.SkillVersion version) {
        return new SkillVersion()
                .skillId(version.skillId())
                .version(version.version())
                .name(version.name())
                .description(version.description())
                .files(version.files().stream()
                        .map(f -> new SkillFile()
                                .path(f.path())
                                .size((int) f.size())
                                .sha256(f.sha256()))
                        .toList())
                .createdBy(version.createdBy())
                .createdAt(Times.utc(version.createdAt()));
    }
}
