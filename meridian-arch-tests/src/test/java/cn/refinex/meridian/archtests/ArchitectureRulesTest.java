package cn.refinex.meridian.archtests;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 架构规则，与架构设计「防漂移机制」逐条对应。
 *
 * <p>只导入生产代码（{@link ImportOption.DoNotIncludeTests}），测试代码不受业务规则约束。
 * 所有规则都允许"没有可检查的类"，所以空模块也能通过。
 *
 * <p>「防漂移机制」表中由其他手段落地的条目：
 * <ul>
 *   <li>禁止依赖 Spring AI、LangChain4j、AgentScope starter、RAG 扩展、Service：maven-enforcer {@code bannedDependencies}，
 *       本类的规则 6 再从包依赖层面兜一次。
 *   <li>模块依赖方向：本类规则 3 检查包依赖，maven-enforcer 在各模块 POM 中检查 Maven 依赖。
 *   <li>依赖版本只在 {@code meridian-bom} 声明：{@code scripts/ci/check-pom-versions.sh}。
 *   <li>{@code HarnessAgent} 装配、事件与停止原因映射穷举、API 与 OpenAPI 一致、凭据不出现在日志中：
 *       由各自任务的单元测试、契约测试与 gitleaks 落地，不属于 ArchUnit。
 * </ul>
 *
 * <p>由后续任务补上的规则（已在 {@code docs/plan/PLAN.md}「登记事项」登记）：
 * <ul>
 *   <li>T10a：平台工具继承 {@code ToolBase}（经平台基类 {@code PlatformTool}）；运行时 Toolkit 中的非 {@code ToolBase}
 *       工具必须在白名单内（启动自检）。平台工具基类在 T10a 创建。
 * </ul>
 */
@AnalyzeClasses(packages = MeridianModule.ROOT_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule rule1_only_engine_agentscope_depends_on_agentscope = noClasses()
            .that()
            .resideOutsideOfPackage("cn.refinex.meridian.engine.agentscope..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.agentscope..")
            .as("规则 1：只有 cn.refinex.meridian.engine.agentscope..（含 engine-agentscope-test 的夹具包）可以依赖 io.agentscope..")
            .because("框架类型不出适配层，见架构设计「架构原则」「模块依赖规则」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule2_runtime_uses_engine_agentscope_only_in_config = noClasses()
            .that()
            .resideInAPackage("cn.refinex.meridian.runtime..")
            .and()
            .resideOutsideOfPackage("cn.refinex.meridian.runtime.config..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("cn.refinex.meridian.engine.agentscope..")
            .as("规则 2：cn.refinex.meridian.runtime.. 中只有 runtime.config.. 可以依赖 cn.refinex.meridian.engine.agentscope..")
            .because("runtime 只通过 meridian-engine-spi 的接口使用内核，见架构设计「模块依赖规则」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule3_modules_respect_dependency_table = CompositeArchRule.of(
                    MeridianModule.everyClassBelongsToAModule())
            .and(CompositeArchRule.of(MeridianModule.dependencyRules()))
            .as("规则 3：每个模块只依赖「模块依赖规则」表允许的模块；三个应用互不依赖")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule4_domain_is_framework_free = noClasses()
            .that()
            .resideInAPackage("cn.refinex.meridian..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "cn.refinex.meridian..infra..")
            .as("规则 4：..domain.. 不依赖 org.springframework.. 与 ..infra..")
            .because("domain 是纯 Java 的实体与规则，见架构设计「包结构」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule5_api_does_not_touch_infra = noClasses()
            .that()
            .resideInAPackage("cn.refinex.meridian..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("cn.refinex.meridian..infra..")
            .as("规则 5：..api.. 不依赖 ..infra..")
            .because("api 层只做 decode → call → map，见编码规范「包与目录规划」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule6_no_banned_ai_frameworks = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.ai..", "dev.langchain4j..")
            .as("规则 6：任何类都不依赖 org.springframework.ai.. 与 dev.langchain4j..")
            .because("架构设计「技术基线」禁止引入 Spring AI 与 LangChain4j")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule7_reactor_only_in_common_context_and_concurrent = noClasses()
            .that()
            .resideInAPackage("cn.refinex.meridian.common..")
            .and()
            .resideOutsideOfPackages("cn.refinex.meridian.common.context..", "cn.refinex.meridian.common.concurrent..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("reactor..")
            .as("规则 7：meridian-common 中只有 common.context.. 与 common.concurrent.. 可以依赖 reactor..")
            .because("见架构设计「模块依赖规则」中 meridian-common 一行")
            .allowEmptyShould(true);

    /** 两类仓储基类（含其子类）；基类自身也算，它们创建 {@code JdbcClient}。 */
    private static final DescribedPredicate<JavaClass> REPOSITORY_BASE_SUBCLASSES = assignableTo(
                    TenantScopedRepository.class)
            .or(assignableTo(SystemRepository.class))
            .as("TenantScopedRepository 或 SystemRepository 的子类");

    @ArchTest
    static final ArchRule rule8_jdbc_access_only_through_repository_bases = noClasses()
            .that()
            .resideInAPackage(MeridianModule.ROOT_PACKAGE + "..")
            .and(DescribedPredicate.not(REPOSITORY_BASE_SUBCLASSES))
            // ScopedStatement 是 TenantScopedRepository 交给子类的语句包装，与基类同属一个包
            .and()
            .resideOutsideOfPackage("cn.refinex.meridian.persistence.tenant..")
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(
                    JdbcClient.class,
                    JdbcTemplate.class,
                    JdbcOperations.class,
                    NamedParameterJdbcTemplate.class,
                    NamedParameterJdbcOperations.class)
            .as("规则 8：JdbcClient 与 JdbcTemplate 只能被 TenantScopedRepository 与 SystemRepository 的子类依赖")
            .because("租户条件由仓储基类强制，绕开基类直接访问数据库会漏掉租户隔离，见架构设计「租户隔离」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule9_system_repositories_live_in_infra_system = classes()
            .that()
            .areAssignableTo(SystemRepository.class)
            .and()
            .doNotHaveFullyQualifiedName(SystemRepository.class.getName())
            // meridian-persistence 自己的 outbox、幂等清理等基础设施不属于任何业务上下文，ADR-0020 豁免
            .and()
            .resideOutsideOfPackage("cn.refinex.meridian.persistence..")
            .should()
            .resideInAPackage("..infra.system..")
            .as("规则 9：SystemRepository 的子类只能放在 ..infra.system.. 包中")
            .because("跨租户的数据访问集中在各上下文的 infra.system 包，便于审查，见架构设计「租户隔离」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule10_system_repositories_declare_cross_tenant_reason = classes()
            .that()
            .areAssignableTo(SystemRepository.class)
            .and()
            .doNotHaveFullyQualifiedName(SystemRepository.class.getName())
            .should()
            .beAnnotatedWith(CrossTenant.class)
            .as("规则 10：SystemRepository 的子类必须标注 @CrossTenant(reason = ...)")
            .because("每个跨租户的仓储都要写明理由，见架构设计「租户隔离」")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule12_engine_spi_is_free_of_agentscope_and_spring = noClasses()
            .that()
            .resideInAPackage("cn.refinex.meridian.engine.spi..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.agentscope..", "org.springframework..")
            .as("规则 12：cn.refinex.meridian.engine.spi.. 不依赖 io.agentscope.. 与 org.springframework..")
            .because("端口与内核契约的签名只出现平台类型，见架构设计「端口」「架构原则」第 2 条")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rule11_api_does_not_use_system_repositories = noClasses()
            .that()
            .resideInAPackage("cn.refinex.meridian..api..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(SystemRepository.class)
            .as("规则 11：..api.. 不能依赖 SystemRepository 的子类")
            .because("SystemRepository 只供本上下文的后台作业与端口实现使用，见架构设计「租户隔离」")
            .allowEmptyShould(true);
}
