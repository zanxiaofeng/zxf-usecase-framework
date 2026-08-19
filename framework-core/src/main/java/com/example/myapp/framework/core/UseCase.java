package com.example.myapp.framework.core;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;

import com.example.myapp.framework.core.exception.StepExecutionException;

/**
 * 用例 = 端点 + 有序 step 管道。
 *
 * <p>由 {@code UseCaseAssembler} 按配置装配，由 framework.web 的 RouterFunction 绑定到 endpoint。
 * 执行语义：顺序执行所有 step，任一步骤抛出 RuntimeException 即包装为
 * {@link StepExecutionException}（携带 useCaseId 与 stepName）后中断管道。
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public final class UseCase {

    private final String id;
    private final String description;
    /** 端点描述；shared 用例为 null（不参与路由，仅作为子用例被内嵌调用） */
    private final EndpointSpec endpoint;
    private final List<Step> steps;
    private final boolean shared;

    /**
     * 依次执行管道内所有 step，返回最终 payload。
     *
     * <p>执行期间把上下文绑定到 {@link StepContextHolder}（同线程 Java 代码可经
     * {@code UseCaseInvoker}（core.invoke 包）继承上下文调用子用例），结束后恢复上一层。</p>
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

    /**
     * 用例对外暴露的端点描述。
     *
     * @param method HTTP 方法（类型即合法性保证，装配期从配置字符串解析，非法值 fail-fast）
     * @param path   URI 模板，支持 {@code {var}} 路径变量
     * @param status 成功时返回的 HTTP 状态码（默认 200）
     */
    public record EndpointSpec(HttpMethod method, String path, int status) {
    }
}
