package com.example.myapp.framework.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用例 = 端点 + 有序 step 管道。
 *
 * <p>由 {@code UseCaseAssembler} 按配置装配，由 framework.web 的 RouterFunction 绑定到 endpoint。
 * 执行语义：顺序执行所有 step，任一步骤抛出 RuntimeException 即包装为
 * {@link StepExecutionException}（携带 useCaseId 与 stepName）后中断管道。
 */
public final class UseCase {

    private static final Logger log = LoggerFactory.getLogger(UseCase.class);

    private final String id;
    private final String description;
    private final EndpointSpec endpoint;
    private final List<Step> steps;
    private final boolean shared;

    public UseCase(String id, String description, EndpointSpec endpoint, List<Step> steps) {
        this(id, description, endpoint, steps, false);
    }

    /**
     * @param endpoint shared 用例可为 null（不参与路由）
     * @param shared   是否共享用例（仅可被子用例 step 嵌入引用）
     */
    public UseCase(String id, String description, EndpointSpec endpoint, List<Step> steps, boolean shared) {
        this.id = id;
        this.description = description;
        this.endpoint = endpoint;
        this.steps = List.copyOf(steps);
        this.shared = shared;
    }

    public boolean isShared() {
        return shared;
    }

    /**
     * 依次执行管道内所有 step，返回最终 payload。
     *
     * <p>执行期间把上下文绑定到 {@link StepContextHolder}（同线程 Java 代码可经
     * {@link UseCaseInvoker} 继承上下文调用子用例），结束后恢复上一层。</p>
     *
     * @throws StepExecutionException 任一步骤失败时抛出（步骤抛出的 StepExecutionException 原样上抛）
     */
    public Object execute(StepContext context) {
        StepContext previous = StepContextHolder.set(context);
        try {
            for (Step step : steps) {
                long start = System.nanoTime();
                try {
                    step.execute(context);
                } catch (StepExecutionException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new StepExecutionException(id, step.name(), e);
                }
                log.debug("usecase [{}] step [{}] finished in {} ms",
                        id, step.name(), (System.nanoTime() - start) / 1_000_000);
            }
            return context.getPayload();
        } finally {
            StepContextHolder.restore(previous);
        }
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public EndpointSpec getEndpoint() {
        return endpoint;
    }

    public List<Step> getSteps() {
        return steps;
    }
}
