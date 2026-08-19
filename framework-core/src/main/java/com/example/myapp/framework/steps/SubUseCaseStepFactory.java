package com.example.myapp.framework.steps;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

/**
 * usecase（子用例调用）步骤工厂。目标用例的存在性与循环引用由 UseCaseAssembler 在装配期统一校验。
 * 执行委托 {@link UseCaseInvoker}（与其 Java 调用入口共享同一上下文编排实现）。
 */
@RequiredArgsConstructor
public final class SubUseCaseStepFactory implements StepFactory {

    public static final String TYPE = "usecase";

    private final Supplier<UseCaseInvoker> invokerSupplier;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        String name = definition.nameOr(TYPE);
        String input = config.stringOr("input", "#payload");
        String as = config.optionalString("as");
        boolean isolate = Boolean.parseBoolean(config.stringOr("isolate", "false"));
        // ref（目标用例 id）的非空与存在性已由装配器校验
        return new SubUseCaseStep(name, definition.ref(), input, as, isolate, invokerSupplier, evaluator);
    }
}
