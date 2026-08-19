package com.example.myapp.framework.steps;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * starter 步骤工厂。config.keys 必填且非空，值必须是字符串表达式。
 */
public final class StarterStepFactory implements StepFactory {

    private final StepExpressionEvaluator evaluator;

    public StarterStepFactory(StepExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public String type() {
        return "starter";
    }

    @Override
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        Map<String, Object> keys = config.mapOrEmpty("keys");
        if (keys.isEmpty()) {
            throw new UseCaseAssemblyException(
                    "step [%s]: config 'keys' is required and must not be empty".formatted(definition.nameOr("starter")));
        }
        Map<String, String> keyExpressions = new LinkedHashMap<>();
        keys.forEach((key, expression) -> {
            if (!(expression instanceof String text) || text.isBlank()) {
                throw new UseCaseAssemblyException(
                        "step [%s]: keys.%s must be a non-blank string expression"
                                .formatted(definition.nameOr("starter"), key));
            }
            keyExpressions.put(key, text);
        });
        return new StarterStep(definition.nameOr("starter"), keyExpressions, evaluator);
    }
}
