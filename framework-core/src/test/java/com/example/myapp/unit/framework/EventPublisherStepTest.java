package com.example.myapp.unit.framework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.EventPublisher;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.EventPublisherStep;
import com.example.myapp.framework.steps.EventPublisherStepFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * eventPublisher 步骤的事务时机语义：
 * 无事务立即发布；事务内注册 afterCommit（提交后才发布）；回滚不发布。
 */
class EventPublisherStepTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);

    /** 记录发布调用与所在时刻的桩发布器 */
    private final List<Object> published = new ArrayList<>();
    private final EventPublisher publisher = published::add;

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private Step step(String eventExpression) {
        // 直接构造 step（发布器解析走工厂逻辑，由 e2e 覆盖真实容器场景）
        return new EventPublisherStep("publish", eventExpression, () -> publisher, evaluator);
    }

    private StepContext contextWithPayload(Map<String, Object> payload) {
        StepContext context = StepContext.standalone();
        context.setPayload(payload);
        return context;
    }

    @Test
    void publishesImmediatelyWithoutTransaction() {
        Step step = step("#payload.id");

        step.execute(contextWithPayload(Map.of("id", "e-1")));

        assertThat(published).containsExactly("e-1");
    }

    @Test
    void publishesOnlyAfterCommitWithinTransaction() {
        Step step = step("#payload.id");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        step.execute(contextWithPayload(Map.of("id", "e-2")));

        assertThat(published).isEmpty();   // 事务内仅注册意图，未外发

        // 模拟提交：触发注册的 afterCommit
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(published).containsExactly("e-2");
    }

    @Test
    void rollbackDoesNotPublish() {
        Step step = step("#payload.id");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        step.execute(contextWithPayload(Map.of("id", "e-3")));
        // 模拟回滚：afterCompletion(ROLLBACK)——不触发 afterCommit，事件不发布
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(published).isEmpty();
    }

    @Test
    void publisherIsResolvedLazilyOnlyOnActualPublish() {
        // 装配期不触碰发布器：supplier 计数只在真正发布时递增
        AtomicInteger resolutions = new AtomicInteger();
        EventPublisher countingPublisher = event -> {
            resolutions.incrementAndGet();
            published.add(event);
        };
        Step step = new EventPublisherStep("publish", "'e-4'", () -> {
            resolutions.incrementAndGet();
            return countingPublisher;
        }, evaluator);

        assertThat(resolutions).hasValue(0);   // 构造/装配期零解析
        step.execute(contextWithPayload(Map.of()));
        assertThat(resolutions).hasValue(2);   // 解析一次 + 发布一次
    }

    @Test
    void eventExpressionRequiredAtAssembly() {
        EventPublisherStepFactory factory = new EventPublisherStepFactory(null, evaluator);
        assertThatThrownBy(() -> factory.create(new StepDefinition("publish", "eventPublisher", null, Map.of())))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("event");
    }

    @Test
    void mapEventIsShallowCopiedBeforePublish() {
        // Fix 8a：Map 事件浅拷贝脱钩顶层——发布后修改源 Map 不影响已发布事件（afterCommit 场景）
        Step step = step("#payload");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "e-5");
        StepContext context = contextWithPayload(source);

        step.execute(context);
        source.put("id", "tampered");   // 模拟后续步骤原地修改源

        assertThat(published).hasSize(1);
        assertThat(published.get(0)).isNotSameAs(source);
        assertThat(((Map<?, ?>) published.get(0)).get("id")).isEqualTo("e-5");   // 浅拷贝脱钩：源改写不影响事件顶层
    }

    @Test
    void eventAliasingPayloadTriggersWarn() {
        // 事件表达式直接引用 #payload → WARN 提示（顶层已浅拷贝隔离，嵌套仍共享）
        Logger stepLogger = (Logger) LoggerFactory.getLogger(EventPublisherStep.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stepLogger.addAppender(appender);
        try {
            Step step = step("#payload");
            step.execute(contextWithPayload(Map.of("id", "e-6")));

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel().toString()).isEqualTo("WARN");
                        assertThat(event.getFormattedMessage()).contains("#payload");
                    });
        } finally {
            stepLogger.detachAppender(appender);
        }
    }
}
