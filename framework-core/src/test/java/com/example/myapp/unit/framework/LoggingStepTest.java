package com.example.myapp.unit.framework;

import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.LoggingStepFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * logging 步骤：消息模板解析、级别路由、按用例+步骤名的 logger category、非法级别装配期报错。
 */
class LoggingStepTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);
    private final LoggingStepFactory factory = new LoggingStepFactory(evaluator);

    private Logger stepLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        stepLogger = (Logger) LoggerFactory.getLogger("usecase.testUc.step.logCredit");
        appender = new ListAppender<>();
        appender.start();
        stepLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        stepLogger.detachAppender(appender);
    }

    @Test
    void rendersMessageTemplateWithBizAndVars() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("level", "INFO");
        config.put("message", "用户 #{biz.businessId} 信用分: #{vars.credit.score}");

        Step step = factory.create(new StepDefinition("logCredit", "logging", null, config)
                .withUseCaseId("testUc"));
        StepContext context = StepContext.standalone();
        context.putBiz("businessId", "u1");
        context.putVar("credit", Map.of("score", 760));

        step.execute(context);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).isEqualTo("用户 u1 信用分: 760");
        assertThat(event.getLevel().toString()).isEqualTo("INFO");
        // logging 步骤不产生数据
        assertThat(context.getPayload()).isNull();
    }

    @Test
    void routesToConfiguredLevel() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("level", "warn");
        config.put("message", "warn-message");

        Step step = factory.create(new StepDefinition("logCredit", "logging", null, config)
                .withUseCaseId("testUc"));
        step.execute(StepContext.standalone());

        assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("WARN");
    }

    @Test
    void invalidLevelFailsFastAtAssembly() {
        assertThatThrownBy(() -> factory.create(new StepDefinition(
                "logCredit", "logging", null, Map.of("level", "verbose")).withUseCaseId("testUc")))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("invalid logging level");
    }
}
