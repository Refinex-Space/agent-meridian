package cn.refinex.meridian.archtests;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台 Java 模块与其包根，以及架构设计「模块依赖规则」表中每个模块允许依赖的模块。
 *
 * <p>表中没有列出的依赖一律禁止。新增模块或修改依赖规则要先提 ADR，再同步修改本枚举。
 */
enum MeridianModule {
    COMMON("meridian-common", "cn.refinex.meridian.common"),
    API("meridian-api", "cn.refinex.meridian.api", COMMON),
    SECURITY("meridian-security", "cn.refinex.meridian.security", COMMON, API),
    PERSISTENCE("meridian-persistence", "cn.refinex.meridian.persistence", COMMON, API),
    ENGINE_SPI("meridian-engine-spi", "cn.refinex.meridian.engine.spi", COMMON),
    ENGINE_AGENTSCOPE("meridian-engine-agentscope", "cn.refinex.meridian.engine.agentscope", COMMON, ENGINE_SPI),
    ENGINE_AGENTSCOPE_TEST(
            "meridian-engine-agentscope-test", "cn.refinex.meridian.engine.agentscope.testkit", ENGINE_AGENTSCOPE),
    MODEL_CLIENT("meridian-model-client", "cn.refinex.meridian.modelclient", COMMON, SECURITY),
    CONTROL_PLANE("meridian-control-plane", "cn.refinex.meridian.controlplane", COMMON, API, SECURITY, PERSISTENCE),
    /** runtime 对 engine-agentscope 的引用只允许在 config 包，由规则 2 进一步收紧。 */
    RUNTIME(
            "meridian-runtime",
            "cn.refinex.meridian.runtime",
            COMMON,
            API,
            SECURITY,
            PERSISTENCE,
            ENGINE_SPI,
            ENGINE_AGENTSCOPE),
    KNOWLEDGE("meridian-knowledge", "cn.refinex.meridian.knowledge", COMMON, API, SECURITY, PERSISTENCE, MODEL_CLIENT),
    TEST_SUPPORT("meridian-test-support", "cn.refinex.meridian.testsupport", COMMON, PERSISTENCE),
    /** 只有测试代码，生产代码中只有包说明。 */
    ARCH_TESTS("meridian-arch-tests", "cn.refinex.meridian.archtests");

    /** 所有平台代码的包根，与 Maven groupId 一致。 */
    static final String ROOT_PACKAGE = "cn.refinex.meridian";

    private final String artifactId;
    private final String rootPackage;
    /** 允许依赖的模块，按「模块依赖规则」表的顺序。 */
    private final List<MeridianModule> allowed;

    MeridianModule(String artifactId, String rootPackage, MeridianModule... allowed) {
        this.artifactId = artifactId;
        this.rootPackage = rootPackage;
        // 枚举构造期间不能使用 EnumSet（常量数组尚未就绪），这里用不可变 List
        this.allowed = List.of(allowed);
    }

    /**
     * 属于本模块的类：位于本模块包根之下，但不在嵌套于其下的其他模块包根中
     * （engine-agentscope-test 的包根嵌套在 engine-agentscope 之下）。
     */
    private DescribedPredicate<JavaClass> ownClasses() {
        List<String> nested = Arrays.stream(values())
                .filter(other -> other != this && other.rootPackage.startsWith(rootPackage + "."))
                .map(other -> other.rootPackage + "..")
                .toList();
        DescribedPredicate<JavaClass> inRoot = resideInAPackage(rootPackage + "..");
        DescribedPredicate<JavaClass> result =
                nested.isEmpty() ? inRoot : inRoot.and(not(resideInAnyPackage(nested.toArray(String[]::new))));
        return result.as(artifactId + " 的类");
    }

    private static DescribedPredicate<JavaClass> classesOfAny(Set<MeridianModule> modules) {
        return modules.stream()
                .map(MeridianModule::ownClasses)
                .reduce((left, right) -> left.or(right))
                .orElseThrow()
                .as(modules.stream().map(m -> m.artifactId).collect(Collectors.joining("、")) + " 的类");
    }

    /** 每个模块一条规则：不依赖「模块依赖规则」表未允许的其他平台模块。 */
    static List<ArchRule> dependencyRules() {
        return Arrays.stream(values())
                .map(module -> {
                    Set<MeridianModule> forbidden = EnumSet.allOf(MeridianModule.class);
                    forbidden.remove(module);
                    forbidden.removeAll(module.allowed);
                    String allowedText = module.allowed.isEmpty()
                            ? "不依赖任何平台模块"
                            : "只依赖 "
                                    + module.allowed.stream()
                                            .map(m -> m.artifactId)
                                            .collect(Collectors.joining("、"));
                    return noClasses()
                            .that(module.ownClasses())
                            .should()
                            .dependOnClassesThat(classesOfAny(forbidden))
                            .as(module.artifactId + " " + allowedText)
                            .allowEmptyShould(true);
                })
                .toList();
    }

    /** 平台代码必须位于某个已登记模块的包根之下，否则规则 3 会漏检。 */
    static ArchRule everyClassBelongsToAModule() {
        String[] roots = Arrays.stream(values()).map(m -> m.rootPackage + "..").toArray(String[]::new);
        return classes()
                .that()
                .resideInAPackage(ROOT_PACKAGE + "..")
                .should()
                .resideInAnyPackage(roots)
                .as("平台代码都位于已登记模块的包根之下")
                .allowEmptyShould(true);
    }
}
