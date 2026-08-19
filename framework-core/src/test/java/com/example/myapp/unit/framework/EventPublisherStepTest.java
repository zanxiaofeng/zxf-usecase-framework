package com.example.myapp.unit.framework;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.EventPublisher;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.EventPublisherStep;
import com.example.myapp.framework.steps.EventPublisherStepFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
}
