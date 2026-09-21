package com.example.usecase.framework.steps;

import java.util.LinkedHashMap;

import lombok.RequiredArgsConstructor;

import com.example.usecase.framework.assemble.StepConfigs;
import com.example.usecase.framework.assemble.StepDefinition;
import com.example.usecase.framework.assemble.StepFactory;
import com.example.usecase.framework.core.spi.Step;
import com.example.usecase.framework.expression.StepExpressionEvaluator;
import com.example.usecase.framework.steps.config.StarterConfig;

/**
 * starter 步骤工厂。config schema 见 {@link StarterConfig}（keys 非空、键与表达式非空白
 * 均由容器元素约束声明式校验）。
 */
@RequiredArgsConstructor
public final class StarterStepFactory implements StepFactory {

    public static final String TYPE = "starter";

    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        StarterConfig config = StepConfigs.bind(definition, StarterConfig.class);
        return new StarterStep(definition.nameOr(TYPE), new LinkedHashMap<>(config.keys()), evaluator);
    }
}
