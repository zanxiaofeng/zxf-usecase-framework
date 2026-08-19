package com.example.myapp.framework.steps;

import java.util.LinkedHashMap;

import lombok.RequiredArgsConstructor;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.StarterConfig;

/**
 * starter 步骤工厂。config schema 见 {@link StarterConfig}（keys 非空、键与表达式非空白
 * 均由容器元素约束声明式校验）。
 */
@RequiredArgsConstructor
public final class StarterStepFactory implements StepFactory {

    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "starter";
    }

    @Override
    public Step create(StepDefinition definition) {
        StarterConfig config = StepConfigs.bind(definition, StarterConfig.class);
        return new StarterStep(definition.nameOr("starter"), new LinkedHashMap<>(config.keys()), evaluator);
    }
}
