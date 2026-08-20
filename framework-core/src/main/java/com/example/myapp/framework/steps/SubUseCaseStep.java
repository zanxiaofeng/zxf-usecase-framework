package com.example.myapp.framework.steps;

import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 子用例调用步骤：把另一个 usecase 嵌入当前管道。
 *
 * <pre>{@code
 * - name: loadUserBase
 *   type: usecase
 *   ref: userBaseEnrichment        # 目标用例 id（装配期校验存在性 + 环检测）
 *   config:
 *     input: "#biz.businessId"     # 子用例初始 payload，缺省 #payload
 *     as: userDto                  # 可选：结果写 #vars.userDto 且父 payload 不动；缺省子结果成为父 payload
 *     isolate: false               # 可选：true 时子用例 vars 隔离、biz 拷贝继承
 * }</pre>
 *
 * <p>执行统一委托 {@link UseCaseInvoker}——与 Java 代码调用子用例共享同一实现，两种入口的
 * 上下文编排语义不会漂移：{@code isolate} → {@link UseCaseInvoker#invokeIsolated}；
 * 其余 → {@link UseCaseInvoker#invoke}（共享上下文，父 payload 自动恢复）。结果统一经
 * {@link StepContext#storeResult} 落地：未配 {@code as} 时写回 payload（串联模式：子结果成为父
 * payload），配置 {@code as} 时旁路到 {@code #vars}。</p>
 *
 * <p>invoker 经 Supplier 延迟解析：step 创建发生在装配期，此时 invoker 依赖的 registry 尚未就绪。</p>
 */
@Slf4j
@RequiredArgsConstructor
public final class SubUseCaseStep implements Step {

    private final String name;
    private final String useCaseId;
    private final String inputExpression;
    private final String as;
    private final boolean isolate;
    private final Supplier<UseCaseInvoker> invokerSupplier;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Object input = evaluator.evaluate(inputExpression, context, name);
        log.debug("sub-usecase step [{}] invoking [{}] (isolate={})", name, useCaseId, isolate);
        UseCaseInvoker invoker = invokerSupplier.get();
        Object result = isolate
                ? invoker.invokeIsolated(useCaseId, input, context)
                : invoker.invoke(useCaseId, input, context);
        context.storeResult(result, as, true);
    }
}
