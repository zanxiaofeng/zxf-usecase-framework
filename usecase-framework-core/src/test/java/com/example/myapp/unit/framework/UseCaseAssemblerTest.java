package com.example.myapp.unit.framework;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.assemble.UseCaseAssembler;
import com.example.myapp.framework.assemble.UseCaseDefinition;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.UseCaseTrace;
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
        return new UseCaseDefinition.Endpoint(HttpMethod.GET, "/x", 200);
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
        return new UseCaseAssembler(new StaticListableBeanFactory(), assemblerWithStarterFactories());
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
        UseCaseDefinition invalid = new UseCaseDefinition("e1", null, false, null, List.of(loadStep()));
        assertThatThrownBy(() -> assembler().assemble(List.of(invalid)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void nonStandardHttpMethodFailsFast() {
        // SF7 的 HttpMethod.valueOf 对未知方法名静默构造自定义实例（不抛异常），
        // 装配期白名单校验负责拦截拼写错误（如 GTE），避免路由永不匹配的静默失效
        UseCaseDefinition invalid = new UseCaseDefinition("e1", null, false,
                new UseCaseDefinition.Endpoint(HttpMethod.valueOf("GTE"), "/x", 200), List.of(loadStep()));
        assertThatThrownBy(() -> assembler().assemble(List.of(invalid)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("not a standard HTTP method");
    }

    @Test
    void subUsecaseStepAssemblesIntoSubUseCaseStep() {
        UseCaseDefinition shared = new UseCaseDefinition("s1", null, true, null, List.of(loadStep()));
        UseCaseDefinition parent = new UseCaseDefinition("p1", null, false, endpoint(),
                List.of(loadStep(), subStep("s1")));

        UseCaseRegistry registry = assembler().assemble(List.of(shared, parent));

        assertThat(registry.require("p1").getSteps().get(1)).isInstanceOf(SubUseCaseStep.class);
    }

    @Test
    void unknownSubUsecaseRefFailsWithAvailableIds() {
        UseCaseDefinition parent = new UseCaseDefinition("p1", null, false, endpoint(),
                List.of(subStep("missing")));
        assertThatThrownBy(() -> assembler().assemble(List.of(parent)))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("unknown sub-usecase ref 'missing'");
    }

    @Test
    void subUsecaseStepWithoutRefFails() {
        UseCaseDefinition parent = new UseCaseDefinition("p1", null, false, endpoint(),
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
        UseCaseDefinition invalid = new UseCaseDefinition("p1", null, false, endpoint(),
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

    // ------------------------------------------------------------------
    // Fix 5/6：vars 键静态分析（as 碰撞 WARN + 数据流报告）
    // ------------------------------------------------------------------

    private StepDefinition loadAsStep(String name, String as) {
        return new StepDefinition(name, "dataLoader", null, Map.of("expression", "'x'", "as", as));
    }

    private ListAppender<ILoggingEvent> attachAssemblerAppender() {
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(UseCaseAssembler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        assemblerLogger.addAppender(appender);
        return appender;
    }

    @Test
    void duplicateAsKeyWithinUsecaseWarns() {
        ListAppender<ILoggingEvent> appender = attachAssemblerAppender();
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(UseCaseAssembler.class);
        try {
            UseCaseDefinition definition = new UseCaseDefinition("p1", null, true, null,
                    List.of(loadAsStep("fetchCredit", "credit"), loadAsStep("reScore", "credit")));
            assembler().assemble(List.of(definition));

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel().toString()).isEqualTo("WARN");
                        assertThat(event.getFormattedMessage())
                                .contains("vars key 'credit'").contains("fetchCredit").contains("reScore")
                                .contains("静态可见范围");
                    });
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }

    @Test
    void asCollisionViaChainedSubUsecaseWarns() {
        // 串联（非 isolate）子用例共享父 vars：子内 as 键与父管道撞名 → WARN（带 childId. 前缀定位）
        ListAppender<ILoggingEvent> appender = attachAssemblerAppender();
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(UseCaseAssembler.class);
        try {
            UseCaseDefinition child = new UseCaseDefinition("s1", null, true, null,
                    List.of(loadAsStep("toDto", "credit")));
            UseCaseDefinition parent = new UseCaseDefinition("p1", null, true, null,
                    List.of(loadAsStep("fetchCredit", "credit"), subStep("s1")));
            assembler().assemble(List.of(child, parent));

            assertThat(appender.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("vars key 'credit'").contains("s1.toDto"));
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }

    @Test
    void isolatedSubUsecaseWritesDoNotCollideWithParent() {
        // isolate 子用例 vars 全新：子内 as 键不与父合并，不触发碰撞 WARN
        ListAppender<ILoggingEvent> appender = attachAssemblerAppender();
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(UseCaseAssembler.class);
        try {
            UseCaseDefinition child = new UseCaseDefinition("s1", null, true, null,
                    List.of(loadAsStep("toDto", "credit")));
            UseCaseDefinition parent = new UseCaseDefinition("p1", null, true, null,
                    List.of(loadAsStep("fetchCredit", "credit"),
                            new StepDefinition("sub", "usecase", "s1", Map.of("isolate", true))));
            assembler().assemble(List.of(child, parent));

            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains("vars key 'credit'"));
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }

    @Test
    void reportEnabledLogsDataflowPerUsecase() {
        ListAppender<ILoggingEvent> appender = attachAssemblerAppender();
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(UseCaseAssembler.class);
        try {
            UseCaseDefinition definition = new UseCaseDefinition("p1", null, true, null,
                    List.of(starterStep(Map.of("businessId", "#path.id")),
                            loadAsStep("fetchCredit", "credit"),
                            new StepDefinition("check", "dataLoader", null,
                                    Map.of("expression", "#vars.credit", "as", "checked"))));
            new UseCaseAssembler(new StaticListableBeanFactory(), assemblerWithStarterFactories(),
                    UseCaseTrace.DISABLED, true).assemble(List.of(definition));

            assertThat(appender.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("dataflow: p1")
                            .contains("start{businessId}")          // biz 写入（starter keys）
                            .contains("credit{fetchCredit}")        // vars 写入（as 键 → 写入点）
                            .contains("vars.credit"));               // vars 读取（表达式静态分析）
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }

    private List<StepFactory> assemblerWithStarterFactories() {
        List<StepFactory> withStarter = new java.util.ArrayList<>(factories);
        withStarter.add(new StarterStepFactory(evaluator));
        return withStarter;
    }
}
