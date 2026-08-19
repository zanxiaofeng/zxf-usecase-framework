package com.example.myapp.framework.steps;

import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.myapp.framework.core.EventPublisher;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 事件发布步骤：求值 {@code event} 表达式构造领域事件，经 {@link EventPublisher} 端口外发。
 *
 * <pre>{@code
 * - name: publishSnapshotCreated
 *   type: eventPublisher
 *   config:
 *     event: "T(com.example.myapp.domain.event.SnapshotCreatedEvent).of(#payload.snapshotId, #body.userId)"
 *     publisher: snapshotEventPublisher   # 可选：多发布器时指定 Bean 名；缺省取容器中唯一的 EventPublisher
 * }</pre>
 *
 * <p>事务时机（architecture §7.3 铁律「禁止事务内外发外部消息」）由本步骤统一保障：
 * 活动事务内注册 {@code afterCommit}，提交成功后才真正外发（回滚不发布）；无事务时立即发布。</p>
 *
 * <p>纯副作用步骤：不改变 payload / vars / biz。</p>
 */
@Slf4j
@RequiredArgsConstructor
public final class EventPublisherStep implements Step {

    private final String name;
    private final String eventExpression;
    private final Supplier<EventPublisher> publisherSupplier;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Object event = evaluator.evaluate(eventExpression, context);
        boolean transactional = TransactionSynchronizationManager.isActualTransactionActive();
        log.debug("event step [{}] publishing {} ({})", name, event.getClass().getSimpleName(),
                transactional ? "after commit" : "immediately");
        if (!transactional) {
            publisherSupplier.get().publish(event);
            return;
        }
        // 事务内：仅注册意图，提交成功后才外发（回滚时 afterCommit 不触发，事件不发布）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisherSupplier.get().publish(event);
            }
        });
    }
}
