package com.example.myapp.unit.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

import com.example.myapp.framework.core.DataLoader;
import com.example.myapp.framework.core.DataSaver;
import com.example.myapp.framework.core.DataSnapshot;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase.EndpointSpec;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseTrace;
import com.example.myapp.framework.core.exception.StepExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 核心管道语义：顺序执行、payload 流转、异常包装。
 */
class UseCaseTest {

    @Test
    void executesStepsInOrderAndFlowsPayload() {
        List<String> trace = new ArrayList<>();
        DataLoader loader = context -> context.setPayload("raw");
        DataTransformer transformer = context -> {
            trace.add("transform");
            context.setPayload(context.getPayload(String.class) + "+transformed");
        };
        DataSaver saver = context -> trace.add("save:" + context.getPayload(String.class));

        UseCase useCase = new UseCase("uc1", "demo", new EndpointSpec(HttpMethod.GET, "/x", 200),
                List.of(loader, transformer, saver), false);
        StepContext context = StepContext.standalone();

        Object result = useCase.execute(context);

        assertThat(result).isEqualTo("raw+transformed");
        assertThat(trace).containsExactly("transform", "save:raw+transformed");
    }

    @Test
    void wrapsStepFailureWithUseCaseIdAndStepName() {
        Step boom = context -> {
            throw new IllegalStateException("boom");
        };
        UseCase useCase = new UseCase("uc2", null, new EndpointSpec(HttpMethod.GET, "/x", 200), List.of(boom), false);
        StepContext context = StepContext.standalone();

        assertThatThrownBy(() -> useCase.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .satisfies(e -> {
                    StepExecutionException see = (StepExecutionException) e;
                    assertThat(see.getUseCaseId()).isEqualTo("uc2");
                    assertThat(see.getStepName()).isNotBlank();
                    assertThat(see.getCause()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void failureCarriesKeyLevelDiagnostics() {
        // Fix 4a：失败现场只带类型与键名（不带值）
        Step boom = context -> {
            throw new IllegalStateException("boom");
        };
        UseCase useCase = new UseCase("uc3", null, null, List.of(boom), false);
        StepContext context = StepContext.standalone();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", "u1");
        context.setPayload(payload);
        context.putVar("credit", Map.of());
        context.putBiz("businessId", "u1");

        assertThatThrownBy(() -> useCase.execute(context))
                .isInstanceOfSatisfying(StepExecutionException.class, see -> {
                    DataSnapshot snapshot = see.getDiagnostics();
                    assertThat(snapshot).isNotNull();
                    assertThat(snapshot.payloadType()).isEqualTo("LinkedHashMap");
                    assertThat(snapshot.varsKeys()).containsExactly("credit");
                    assertThat(snapshot.bizKeys()).containsExactly("businessId");
                });
    }

    @Test
    void nestedFailureKeepsInnermostDiagnostics() {
        // 嵌套子用例：子的异常已带子的现场，父层附加不得覆盖（最内层优先）
        Step boom = context -> {
            throw new IllegalStateException("boom");
        };
        UseCase inner = new UseCase("inner", null, null, List.of(boom), true);
        Step parentStep = context -> {
            try {
                inner.execute(StepContext.standalone());
            } catch (StepExecutionException e) {
                throw e;   // 模拟 SubUseCaseStep：子异常原样上抛
            }
        };
        UseCase outer = new UseCase("outer", null, null, List.of(parentStep), false);
        StepContext parentContext = StepContext.standalone();
        parentContext.putVar("outerOnly", "x");

        assertThatThrownBy(() -> outer.execute(parentContext))
                .isInstanceOfSatisfying(StepExecutionException.class, see -> {
                    assertThat(see.getUseCaseId()).isEqualTo("inner");        // 未被父层重包
                    assertThat(see.getDiagnostics().varsKeys()).isEmpty();     // 最内层现场（子上下文无 vars）
                });
    }

    @Test
    void traceEnabledLogsPerStepInfo() {
        // Fix 4b：trace 开启时每步输出 INFO 轨迹（payload 类型迁移 + 新增 vars 键）
        Logger useCaseLogger = (Logger) LoggerFactory.getLogger(UseCase.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        useCaseLogger.addAppender(appender);
        try {
            Step loader = context -> context.setPayload("raw");
            Step enrich = context -> context.putVar("credit", 760);
            UseCase useCase = new UseCase("uc4", null, null, List.of(loader, enrich), false,
                    new UseCaseTrace(true, false));

            useCase.execute(StepContext.standalone());

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel().toString().equals("INFO"))
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("usecase [uc4]").contains("payload null -> String"))
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("vars +[credit]"));
        } finally {
            useCaseLogger.detachAppender(appender);
        }
    }

    @Test
    void traceDisabledProducesNoInfoTrace() {
        Logger useCaseLogger = (Logger) LoggerFactory.getLogger(UseCase.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        useCaseLogger.addAppender(appender);
        try {
            UseCase useCase = new UseCase("uc5", null, null,
                    List.of(context -> context.setPayload("x")), false, UseCaseTrace.DISABLED);

            useCase.execute(StepContext.standalone());

            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains("trace:"));
        } finally {
            useCaseLogger.detachAppender(appender);
        }
    }
}
