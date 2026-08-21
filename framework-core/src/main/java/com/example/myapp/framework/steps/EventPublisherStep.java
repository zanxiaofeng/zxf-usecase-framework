package com.example.myapp.framework.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Object event = evaluator.evaluate(eventExpression, context, name);
        if (event == null) {
            // 表达式求值为 null 属管道/配置缺陷（如引用了不存在的 vars 键）：fail-fast 显式报错，
            // 否则下方 .getClass() 裸 NPE 被包成无语义 500；如需按 400 处理可用 usecase.error-mappings 覆盖
            throw new IllegalStateException(
                    "event step [%s]: event expression evaluated to null".formatted(name));
        }
        if (event == context.getPayload()) {
            // 事件直接引用 payload：后续步骤的原地修改会污染 afterCommit 才发出的事件（顶层已浅拷贝隔离，嵌套结构仍共享）
            log.warn("event step [{}]: 事件表达式直接引用 #payload，建议构造新对象——后续步骤原地修改嵌套结构仍会污染 afterCommit 事件",
                    name);
        }
        // 别名防护：Map/List 事件浅拷贝脱钩顶层引用（深拷贝代价过大，嵌套不变性属数据纪律约定，见 README 扩展指南）
        Object eventToPublish = switch (event) {
            case Map<?, ?> map -> new LinkedHashMap<>(map);
            case List<?> list -> new ArrayList<>(list);
            default -> event;
        };
        boolean transactional = TransactionSynchronizationManager.isActualTransactionActive();
        log.debug("event step [{}] publishing {} ({})", name, eventToPublish.getClass().getSimpleName(),
                transactional ? "after commit" : "immediately");
        if (!transactional) {
            publisherSupplier.get().publish(eventToPublish);
            return;
        }
        // 事务内：仅注册意图，提交成功后才外发（回滚时 afterCommit 不触发，事件不发布）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisherSupplier.get().publish(eventToPublish);
            }
        });
    }
}
