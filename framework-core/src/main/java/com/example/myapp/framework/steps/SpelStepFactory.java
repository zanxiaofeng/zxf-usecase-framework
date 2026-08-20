package com.example.myapp.framework.steps;

import lombok.RequiredArgsConstructor;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.SpelStepConfig;

/**
 * SpEL 三件套（dataLoader / dataTransformer / dataSaver）共用的工厂，按角色区分实例。
 * config schema 见 {@link SpelStepConfig}。
 */
@RequiredArgsConstructor
public final class SpelStepFactory implements StepFactory {

    public enum Role {
        LOADER, TRANSFORMER, SAVER
    }

    private final String type;
    private final Role role;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return type;
    }

    @Override
    public Step create(StepDefinition definition) {
        SpelStepConfig config = StepConfigs.bind(definition, SpelStepConfig.class);
        String name = definition.nameOr(type);
        return switch (role) {
            case LOADER -> new SpelDataLoaderStep(name, config.getExpression(), config.getAs(), evaluator);
            case TRANSFORMER -> new SpelDataTransformerStep(name, config.getExpression(), config.getAs(),
                    config.getOnNull(), evaluator);
            case SAVER -> new SpelDataSaverStep(name, config.getExpression(), config.getAs(), evaluator);
        };
    }
}
