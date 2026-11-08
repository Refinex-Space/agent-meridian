package cn.refinex.meridian.engine.agentscope.testkit.model;

import cn.refinex.meridian.engine.agentscope.model.ProviderClientFactory;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用的 OpenAI 兼容供应商工厂：按供应商侧模型名返回登记的底层模型（通常是 {@link ScriptedModel}），记录每次调用的选项。
 *
 * <p>线程安全：可被并发调用。
 */
public final class StaticProviderClientFactory implements ProviderClientFactory {

    private final Map<String, Model> models = new ConcurrentHashMap<>();
    private final List<GenerateOptions> callOptions = new CopyOnWriteArrayList<>();

    /**
     * 登记底层模型。
     *
     * @param upstreamModel 供应商侧模型名
     * @param model 底层模型
     * @return 本工厂
     */
    public StaticProviderClientFactory register(String upstreamModel, Model model) {
        models.put(upstreamModel, model);
        return this;
    }

    @Override
    public Provider provider() {
        return Provider.OPENAI_COMPATIBLE;
    }

    @Override
    public Model create(ModelRoute route) {
        Model model = models.get(route.upstreamModel());
        if (model == null) {
            throw new IllegalStateException("没有登记底层模型：" + route.upstreamModel());
        }
        return model;
    }

    @Override
    public GenerateOptions callOptions(ModelRoute route, GenerateOptions options) {
        callOptions.add(options);
        return options;
    }

    /**
     * 每次调用的选项，按调用顺序。
     *
     * @return 选项
     */
    public List<GenerateOptions> callOptions() {
        return List.copyOf(callOptions);
    }
}
