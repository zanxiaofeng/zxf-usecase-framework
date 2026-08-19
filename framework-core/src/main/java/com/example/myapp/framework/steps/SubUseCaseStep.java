package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.SubUseCase;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

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
 * <p>registry 经 Supplier 延迟解析：step 创建发生在装配期，此时 registry 尚未就绪。</p>
 */
public final class SubUseCaseStep implements SubUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubUseCaseStep.class);

    private final String name;
    private final String useCaseId;
    private final String inputExpression;
    private final String as;
    private final boolean isolate;
    private final Supplier<UseCaseRegistry> registrySupplier;
    private final StepExpressionEvaluator evaluator;

    public SubUseCaseStep(String name, String useCaseId, String inputExpression, String as, boolean isolate,
                          Supplier<UseCaseRegistry> registrySupplier, StepExpressionEvaluator evaluator) {
        this.name = name;
        this.useCaseId = useCaseId;
        this.inputExpression = inputExpression;
        this.as = as;
        this.isolate = isolate;
        this.registrySupplier = registrySupplier;
        this.evaluator = evaluator;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        UseCase target = registrySupplier.get().require(useCaseId);
        Object input = evaluator.evaluate(inputExpression, context);
        log.debug("sub-usecase step [{}] invoking [{}] (isolate={})", name, useCaseId, isolate);

        if (isolate) {
            // 隔离模式：vars 全新（不污染父），biz 拷贝继承（子的修改不回传）
            StepContext childContext = new StepContext(context.getRequest());
            childContext.getBiz().putAll(context.getBiz());
            childContext.setPayload(input);
            Object result = target.execute(childContext);
            StepResultStore.store(context, result, as, true);
            return;
        }

        // 共享模式：vars / biz 与父共享同一实例；payload 按 as 规则处理
        Object parentPayload = context.getPayload();
        context.setPayload(input);
        Object result = target.execute(context);
        if (as != null) {
            context.putVar(as, result);
            context.setPayload(parentPayload);   // 恢复父 payload（旁路调用）
        }
        // 无 as：子用例结果自然成为父 payload（串联模式）
    }
}
