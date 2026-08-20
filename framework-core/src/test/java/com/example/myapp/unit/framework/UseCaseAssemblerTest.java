package com.example.myapp.unit.framework;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.assemble.UseCaseAssembler;
import com.example.myapp.framework.assemble.UseCaseDefinition;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.SpelStepFactory;
import com.example.myapp.framework.steps.StarterStepFactory;
import com.example.myapp.framework.steps.SubUseCaseStep;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配器三遍校验：shared 用例端点豁免、子用例引用存在性、循环引用检测、step ref/type 互斥。
 */
class UseCaseAssemblerTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);
    private final List<StepFactory> factories = List.of(
            new SpelStepFactory("dataLoader", SpelStepFactory.Role.LOADER, evaluator),
            new SubUseCaseStepFactory(() -> null, evaluator));   // 装配期不触发 invoker 解析

    private UseCaseAssembler assembler() {
        return new UseCaseAssembler(new StaticListableBeanFactory(), factories);
    }

    private UseCaseDefinition.Endpoint endpoint() {
        return new UseCaseDefinition.Endpoint("GET", "/x", null);
    }

    private StepDefinition loadStep() {
        return new StepDefinition("load", "dataLoader", null, Map.of("expression", "'x'"));
    }

    private StepDefinition subStep(String ref) {
        return new StepDefinition("sub", "usecase", ref, Map.of());
    }

    private StepDefinition starterStep(Map<String, String> keys) {
        return new StepDefinition("start", "starter", null, Map.of("keys", keys));
    }

    /** 含 starter 工厂的装配器（starter 步骤需走第三遍构建） */
    private UseCaseAssembler assemblerWithStarter() {
        List<StepFactory> withStarter = new java.util.ArrayList<>(factories);
        withStarter.add(new StarterStepFactory(evaluator));
        return new UseCaseAssembler(new StaticListableBeanFactory(), withStarter);
    }

    @Test
    void sharedUsecaseWithoutEndpointAssembles() {
        UseCaseDefinition shared = new UseCaseDefinition("s1", null, true, null, List.of(loadStep()));
        UseCaseRegistry registry = assembler().assemble(List.of(shared));

        UseCase useCase = registry.require("s1");
        assertThat(useCase.isShared()).isTrue();
        assertThat(useCase.getEndpoint()).isNull();
    }

    @Test
    void nonSharedUsecaseWithoutEndpointFails() {
        UseCaseDefinition invalid = new UseCaseDefinition("e1", null, null, null, List.of(loadStep()));
        assertThatThrownBy(() -> assembler().assemble(List.of(invalid)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void subUsecaseStepAssemblesIntoSubUseCaseStep() {
        UseCaseDefinition shared = new UseCaseDefinition("s1", null, true, null, List.of(loadStep()));
        UseCaseDefinition parent = new UseCaseDefinition("p1", null, null, endpoint(),
                List.of(loadStep(), subStep("s1")));

        UseCaseRegistry registry = assembler().assemble(List.of(shared, parent));

        assertThat(registry.require("p1").getSteps().get(1)).isInstanceOf(SubUseCaseStep.class);
    }

    @Test
    void unknownSubUsecaseRefFailsWithAvailableIds() {
        UseCaseDefinition parent = new UseCaseDefinition("p1", null, null, endpoint(),
                List.of(subStep("missing")));
        assertThatThrownBy(() -> assembler().assemble(List.of(parent)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("unknown sub-usecase ref 'missing'");
    }

    @Test
    void subUsecaseStepWithoutRefFails() {
        UseCaseDefinition parent = new UseCaseDefinition("p1", null, null, endpoint(),
                List.of(new StepDefinition("sub", "usecase", null, Map.of())));
        assertThatThrownBy(() -> assembler().assemble(List.of(parent)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("requires 'ref'");
    }

    @Test
    void circularReferenceAcrossUsecasesFails() {
        UseCaseDefinition a = new UseCaseDefinition("a", null, true, null, List.of(subStep("b")));
        UseCaseDefinition b = new UseCaseDefinition("b", null, true, null, List.of(subStep("a")));

        assertThatThrownBy(() -> assembler().assemble(List.of(a, b)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("circular");
    }

    @Test
    void selfReferenceFails() {
        UseCaseDefinition self = new UseCaseDefinition("self", null, true, null, List.of(subStep("self")));
        assertThatThrownBy(() -> assembler().assemble(List.of(self)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("circular");
    }

    @Test
    void refAndTypeTogetherFailsForNonUsecaseTypes() {
        UseCaseDefinition invalid = new UseCaseDefinition("p1", null, null, endpoint(),
                List.of(new StepDefinition("s", "dataLoader", "someBean", Map.of("expression", "'x'"))));
        assertThatThrownBy(() -> assembler().assemble(List.of(invalid)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("exactly one of 'ref' or 'type'");
    }

    @Test
    void duplicateUsecaseIdFailsAtFirstPass() {
        UseCaseDefinition first = new UseCaseDefinition("dup", null, true, null, List.of(loadStep()));
        UseCaseDefinition second = new UseCaseDefinition("dup", null, true, null, List.of(loadStep()));
        assertThatThrownBy(() -> assembler().assemble(List.of(first, second)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("duplicate usecase id: dup");
    }

    @Test
    void starterWritingReservedBizKeyFailsAtAssembly() {
        // traceId 由 Web 入口白名单校验后种子化，starter 可写会绕过该校验 → 装配期 fail-fast
        UseCaseDefinition invalid = new UseCaseDefinition("p1", null, true, null,
                List.of(starterStep(Map.of("traceId", "'hijacked'", "businessId", "'b1'"))));
        assertThatThrownBy(() -> assembler().assemble(List.of(invalid)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("reserved biz key 'traceId'");
    }

    @Test
    void sharedUsecaseWithStarterWarnsAtAssembly() {
        // shared 用例内含 starter：串联嵌入时共享上下文，子的 starter 会覆写父管道 biz → WARN 提示
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(UseCaseAssembler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        assemblerLogger.addAppender(appender);
        try {
            UseCaseDefinition shared = new UseCaseDefinition("s1", null, true, null,
                    List.of(starterStep(Map.of("businessId", "'b1'"))));
            assemblerWithStarter().assemble(List.of(shared));

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel().toString()).isEqualTo("WARN");
                        assertThat(event.getFormattedMessage()).contains("s1").contains("覆写父管道 biz");
                    });
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }
}
