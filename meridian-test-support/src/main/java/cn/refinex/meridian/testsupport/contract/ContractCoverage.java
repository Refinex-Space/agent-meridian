package cn.refinex.meridian.testsupport.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 契约测试的覆盖记录（R2，ADR-0029）：各服务的测试客户端每按规范校验一次交互，就按真实的方法与路径记下它命中的操作；路径按规范中的
 * 路径模板匹配（{@code {参数}} 匹配一个路径段，字面字符最多的模板优先，{@code /sessions/{id}:archive} 不会被记成
 * {@code /sessions/{id}}），不由调用方自报操作名。每个服务排在最后的 {@code ContractCoverageIT} 断言自己提供的操作都被记到过。
 *
 * <p>规范从类路径读取（{@code meridian-api} 的 {@code openapi/*.yaml}）。线程安全：记录用并发集合。
 */
public final class ContractCoverage {

    /** 公开 API 规范。 */
    public static final String PUBLIC = "openapi/meridian-v1.yaml";

    /** 管理 API 规范。 */
    public static final String ADMIN = "openapi/meridian-admin-v1.yaml";

    /** 内部接口规范。 */
    public static final String INTERNAL = "openapi/meridian-internal-v1.yaml";

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private static final Pattern PARAMETER = Pattern.compile("\\{[^}/]+}");

    private static final Map<String, List<Operation>> SPECS = new ConcurrentHashMap<>();

    private static final Map<String, Set<String>> COVERED = new ConcurrentHashMap<>();

    private ContractCoverage() {}

    /**
     * 记下一次按规范校验过的交互。路径不在规范中时不记（校验库会另外报错）。
     *
     * @param spec 规范的类路径资源
     * @param method HTTP 方法
     * @param rawPath 请求路径，可以带查询串
     */
    public static void record(String spec, String method, String rawPath) {
        String path = rawPath.contains("?") ? rawPath.substring(0, rawPath.indexOf('?')) : rawPath;
        match(spec, method, path)
                .ifPresent(operation -> COVERED.computeIfAbsent(spec, key -> ConcurrentHashMap.newKeySet())
                        .add(operation.name()));
    }

    /**
     * 规范中带指定标签之一的操作里，还没有被记到的。
     *
     * @param spec 规范的类路径资源
     * @param tags 本服务提供的标签
     * @return 操作名（{@code METHOD 路径模板}），按字典序
     */
    public static Set<String> uncovered(String spec, Collection<String> tags) {
        Set<String> covered = COVERED.getOrDefault(spec, Set.of());
        Set<String> missing = new TreeSet<>();
        for (Operation operation : operations(spec)) {
            if (operation.tags().stream().anyMatch(tags::contains) && !covered.contains(operation.name())) {
                missing.add(operation.name());
            }
        }
        return missing;
    }

    /**
     * 规范中带指定标签之一的操作。
     *
     * @param spec 规范的类路径资源
     * @param tags 标签
     * @return 操作名，按字典序
     */
    public static Set<String> operations(String spec, Collection<String> tags) {
        Set<String> names = new TreeSet<>();
        for (Operation operation : operations(spec)) {
            if (operation.tags().stream().anyMatch(tags::contains)) {
                names.add(operation.name());
            }
        }
        return names;
    }

    /**
     * 规范中出现的全部标签。
     *
     * @param spec 规范的类路径资源
     * @return 标签，按字典序
     */
    public static Set<String> tags(String spec) {
        Set<String> tags = new TreeSet<>();
        operations(spec).forEach(operation -> tags.addAll(operation.tags()));
        return tags;
    }

    /**
     * 按方法与路径找规范中的操作。
     *
     * @param spec 规范的类路径资源
     * @param method HTTP 方法
     * @param path 不带查询串的路径
     * @return 操作；不在规范中时为空
     */
    public static Optional<Operation> match(String spec, String method, String path) {
        String upper = method.toUpperCase(Locale.ROOT);
        return operations(spec).stream()
                .filter(operation -> operation.method().equals(upper))
                .filter(operation -> operation.pattern().matcher(path).matches())
                .max(Comparator.comparingInt(Operation::literalLength));
    }

    private static List<Operation> operations(String spec) {
        return SPECS.computeIfAbsent(spec, ContractCoverage::load);
    }

    @SuppressWarnings("unchecked")
    private static List<Operation> load(String spec) {
        Map<String, Object> document;
        try (InputStream in = ContractCoverage.class.getClassLoader().getResourceAsStream(spec)) {
            if (in == null) {
                throw new IllegalStateException("类路径上没有规范：" + spec);
            }
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Operation> operations = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        paths.forEach((template, item) -> ((Map<String, Object>) item).forEach((method, operation) -> {
            if (METHODS.contains(method)) {
                Object tags = ((Map<String, Object>) operation).get("tags");
                operations.add(Operation.of(
                        method.toUpperCase(Locale.ROOT), template, tags == null ? List.of() : (List<String>) tags));
            }
        }));
        return List.copyOf(operations);
    }

    /**
     * 规范中的一个操作。
     *
     * @param method 大写的 HTTP 方法
     * @param template 路径模板
     * @param tags 标签
     * @param pattern 匹配真实路径的正则
     * @param literalLength 模板中参数之外的字符数，多个模板都匹配时取最大的
     */
    public record Operation(String method, String template, List<String> tags, Pattern pattern, int literalLength) {

        static Operation of(String method, String template, List<String> tags) {
            StringBuilder regex = new StringBuilder();
            Matcher parameters = PARAMETER.matcher(template);
            int last = 0;
            int literal = 0;
            while (parameters.find()) {
                regex.append(Pattern.quote(template.substring(last, parameters.start())));
                regex.append("[^/]+");
                literal += parameters.start() - last;
                last = parameters.end();
            }
            regex.append(Pattern.quote(template.substring(last)));
            literal += template.length() - last;
            return new Operation(method, template, List.copyOf(tags), Pattern.compile(regex.toString()), literal);
        }

        /**
         * 操作名。
         *
         * @return {@code METHOD 路径模板}
         */
        public String name() {
            return method + " " + template;
        }
    }
}
