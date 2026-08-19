package com.example.myapp.framework.steps;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * starter 步骤工厂。config.keys 必填且非空，值必须是字符串表达式。
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
        String name = definition.nameOr("starter");
        StepConfig config = StepConfig.of(definition);
        Map<String, Object> keys = config.mapOrEmpty("keys");
        if (keys.isEmpty()) {
            throw new UseCaseAssemblyException(
                    "step [%s]: config 'keys' is required and must not be empty".formatted(name));
        }
        Map<String, String> keyExpressions = new LinkedHashMap<>();
        keys.forEach((key, expression) -> {
            if (!(expression instanceof String text) || text.isBlank()) {
                throw new UseCaseAssemblyException(
                        "step [%s]: keys.%s must be a non-blank string expression".formatted(name, key));
            }
            keyExpressions.put(key, text);
        });
        return new StarterStep(name, keyExpressions, evaluator);
    }
}
