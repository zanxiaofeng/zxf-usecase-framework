package com.example.myapp.framework.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.util.Assert;

import com.example.myapp.framework.core.exception.StepExecutionException;

/**
 * 用例 = 端点 + 有序 step 管道。
 *
 * <p>由 {@code UseCaseAssembler} 按配置装配，由 framework.web 的 RouterFunction 绑定到 endpoint。
 * 执行语义：顺序执行所有 step，任一步骤抛出 RuntimeException 即包装为
 * {@link StepExecutionException}（携带 useCaseId、stepName 与键级数据现场 {@link DataSnapshot}）后中断管道。</p>
 *
 * <p>dev 模式 trace（{@code usecase.trace.enabled}）开启时每步输出 INFO 轨迹（payload 类型迁移、
 * 新增 vars 键、耗时；值快照需 {@code include-values} 二次开启）；关闭时执行路径零开销。</p>
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public final class UseCase {

    private final String id;
    private final @Nullable String description;
    /** 端点描述；shared 用例为 null（不参与路由，仅作为子用例被内嵌调用） */
    private final @Nullable EndpointSpec endpoint;
    private final List<Step> steps;
    private final boolean shared;
    /** dev trace 开关（装配期注入；手工装配/测试默认关闭） */
    private final UseCaseTrace trace;

    /** 便捷构造：无 dev trace（测试与手工装配用） */
    public UseCase(String id, String description, EndpointSpec endpoint, List<Step> steps, boolean shared) {
        this(id, description, endpoint, steps, shared, UseCaseTrace.DISABLED);
    }

    /**
     * 依次执行管道内所有 step，返回最终 payload。
     *
     * <p>执行期间把上下文绑定到 {@link StepContextHolder}（同线程 Java 代码可经
     * {@code UseCaseInvoker}（core.invoke 包）继承上下文调用子用例），结束后恢复上一层。</p>
     *
     * @throws StepExecutionException 任一步骤失败时抛出（步骤抛出的 StepExecutionException 原样上抛，
     *         附最内层数据现场）
     */
    public @Nullable Object execute(StepContext context) {
        Assert.notNull(context, "context must not be null");
        StepContext previous = StepContextHolder.set(context);
        try {
            for (Step step : steps) {
                long start = System.nanoTime();
                TraceSample before = trace.enabled() ? TraceSample.of(context, trace.includeValues()) : null;
                try {
                    step.execute(context);
                } catch (StepExecutionException e) {
                    throw e.withDiagnostics(DataSnapshot.of(context));
                } catch (RuntimeException e) {
                    throw new StepExecutionException(id, step.name(), e).withDiagnostics(DataSnapshot.of(context));
                }
                if (before != null) {   // before 非空 ⟺ trace.enabled()（采样仅在 trace 开启时发生）
                    traceStep(step, before, context, System.nanoTime() - start);
                }
                log.debug("usecase [{}] step [{}] finished in {} ms",
                        id, step.name(), (System.nanoTime() - start) / 1_000_000);
            }
            return context.getPayload();
        } finally {
            StepContextHolder.restore(previous);
        }
    }

    /** dev trace：输出本步的 payload 类型迁移与新增 vars 键（值快照仅 include-values 开启时，截断输出） */
    private void traceStep(Step step, TraceSample before, StepContext context, long nanos) {
        Set<String> addedVars = new LinkedHashSet<>(context.getVars().keySet());
        addedVars.removeAll(before.varsKeys());
        if (trace.includeValues()) {
            log.info("usecase [{}] step [{}] trace: payload {} -> {} ({} -> {}), vars +{}, {} ms",
                    id, step.name(), before.payloadType(), typeOf(context.getPayload()),
                    before.payloadValue(), truncate(String.valueOf(context.getPayload())),
                    addedVars, nanos / 1_000_000);
            return;
        }
        log.info("usecase [{}] step [{}] trace: payload {} -> {}, vars +{}, {} ms",
                id, step.name(), before.payloadType(), typeOf(context.getPayload()), addedVars, nanos / 1_000_000);
    }

    private static String typeOf(@Nullable Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }

    private static String truncate(String value) {
        return value.length() <= UseCaseTrace.VALUE_SNAPSHOT_LIMIT
                ? value
                : value.substring(0, UseCaseTrace.VALUE_SNAPSHOT_LIMIT) + "...";
    }

    /** trace 开启时的步前采样（键集防御性拷贝；关闭时不采样，零开销） */
    private record TraceSample(String payloadType, @Nullable String payloadValue, Set<String> varsKeys) {
        static TraceSample of(StepContext context, boolean includeValues) {
            return new TraceSample(
                    typeOf(context.getPayload()),
                    includeValues ? truncate(String.valueOf(context.getPayload())) : null,
                    Set.copyOf(context.getVars().keySet()));
        }
    }

    /**
     * 用例对外暴露的端点描述。
     *
     * @param method HTTP 方法（强类型，由配置绑定期经 {@code HttpMethod.valueOf} 转换；
     *               SF7 起未知方法名会构造自定义实例而非报错，请使用标准大写方法名）
     * @param path   URI 模板，支持 {@code {var}} 路径变量
     * @param status 成功时返回的 HTTP 状态码（默认 200）
     */
    public record EndpointSpec(HttpMethod method, String path, int status) {
    }
}
