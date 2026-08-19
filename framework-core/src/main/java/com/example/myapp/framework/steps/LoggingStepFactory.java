package com.example.myapp.framework.steps;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.LoggingConfig;

/**
 * logging 步骤工厂。config schema 见 {@link LoggingConfig}（level 合法性由枚举类型保证）。
 * 按 {@code usecase.<useCaseId>.step.<stepName>} 约定创建步骤 logger（category 契约见 {@link LoggingStep}）。
 */
@RequiredArgsConstructor
public final class LoggingStepFactory implements StepFactory {

    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return "logging";
    }

    @Override
    public Step create(StepDefinition definition) {
        LoggingConfig config = StepConfigs.bind(definition, LoggingConfig.class);
        String name = definition.nameOr("logging");
        Logger log = LoggerFactory.getLogger("usecase." + definition.useCaseId() + ".step." + name);
        return new LoggingStep(name, config.getMessage(), config.getLevel(), evaluator, log);
    }
}
