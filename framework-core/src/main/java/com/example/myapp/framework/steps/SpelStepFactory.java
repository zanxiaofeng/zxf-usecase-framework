package com.example.myapp.framework.steps;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * SpEL 三件套（dataLoader / dataTransformer / dataSaver）共用的工厂，按角色区分实例。
 */
public final class SpelStepFactory implements StepFactory {

    public enum Role {
        LOADER, TRANSFORMER, SAVER
    }

    private final String type;
    private final Role role;
    private final StepExpressionEvaluator evaluator;

    public SpelStepFactory(String type, Role role, StepExpressionEvaluator evaluator) {
        this.type = type;
        this.role = role;
        this.evaluator = evaluator;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        String expression = config.requiredString("expression");
        String as = config.optionalString("as");
        String name = definition.nameOr(type);
        return switch (role) {
            case LOADER -> new SpelDataLoaderStep(name, expression, as, evaluator);
            case TRANSFORMER -> new SpelDataTransformerStep(name, expression, as, evaluator);
            case SAVER -> new SpelDataSaverStep(name, expression, as, evaluator);
        };
    }
}
