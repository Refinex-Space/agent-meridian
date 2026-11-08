package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.runtime.modelrouting.app.BudgetLimitPort;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetLevel;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 从开发配置目录 {@code dev-config/budgets} 读取预算硬阈值的 {@link BudgetLimitPort}（T09a；T16 起改为 control-plane 同步的限额）。
 *
 * <p>每个文件是一个 {@code kind: budget}：{@code level}（{@code tenant}、{@code project}、{@code api_key}、{@code session}）、
 * {@code scope_id}（具体 ID，或 {@code "*"} 表示该级别的所有范围）、{@code hard_limit_tokens}。同一级别具体 ID 优先于 {@code "*"}；
 * 没有配置的级别不限。目录不存在时全部不限。
 *
 * <p>每次调用都重新读取目录。方法会阻塞。线程安全：无状态。
 */
public final class FileBudgetLimits implements BudgetLimitPort {

    /** 预算文档的 {@code kind}。 */
    static final String KIND_BUDGET = "budget";

    /** 匹配该级别所有范围的 {@code scope_id}。 */
    static final String ANY_SCOPE = "*";

    private final Path directory;

    /**
     * 创建限额来源。
     *
     * @param directory 开发配置目录
     */
    public FileBudgetLimits(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public long hardLimit(BudgetLevel level, String scopeId) {
        long wildcard = BudgetScope.UNLIMITED;
        for (Map<String, Object> document : YamlDocuments.load(directory)) {
            if (!KIND_BUDGET.equals(document.get("kind"))
                    || BudgetLevel.of(YamlDocuments.required(document, "level")) != level) {
                continue;
            }
            String scope = YamlDocuments.required(document, "scope_id");
            long limit = YamlDocuments.number(document, "hard_limit_tokens", BudgetScope.UNLIMITED);
            if (scope.equals(scopeId)) {
                return limit;
            }
            if (ANY_SCOPE.equals(scope)) {
                wildcard = limit;
            }
        }
        return wildcard;
    }
}
