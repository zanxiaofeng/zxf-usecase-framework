package com.example.myapp.framework.steps;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * logging 步骤工厂。按 {@code usecase.<useCaseId>.step.<stepName>} 约定创建步骤 logger（category 契约见 {@link LoggingStep}）。
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
        StepConfig config = StepConfig.of(definition);
        String name = definition.nameOr("logging");
        String raw = config.stringOr("level", "INFO");
        Level level;
        try {
            level = Level.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UseCaseAssemblyException(
                    "step [%s]: invalid logging level '%s', expected TRACE/DEBUG/INFO/WARN/ERROR"
                            .formatted(name, raw), e);
        }
        String message = config.optionalString("message");
        Logger log = LoggerFactory.getLogger("usecase." + definition.useCaseId() + ".step." + name);
        return new LoggingStep(name, message, level, evaluator, log);
    }
}
