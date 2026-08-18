package com.example.myapp.framework.steps;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * logging 步骤工厂。
 */
public final class LoggingStepFactory implements StepFactory {

    private final StepExpressionEvaluator evaluator;

    public LoggingStepFactory(StepExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public String type() {
        return "logging";
    }

    @Override
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        String name = definition.nameOr("logging");
        LoggingStep.Level level;
        try {
            level = LoggingStep.parseLevel(config.stringOr("level", "INFO"), name);
        } catch (IllegalArgumentException e) {
            throw new UseCaseAssemblyException(e.getMessage(), e);
        }
        String message = config.optionalString("message");
        return new LoggingStep(name, message, level, evaluator);
    }
}
