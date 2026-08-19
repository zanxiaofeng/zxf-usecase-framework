package com.example.myapp.framework.core;

/**
 * 领域事件发布端口（出端口 SPI）。
 *
 * <p>业务侧实现本接口完成真实外发（Kafka / 事务性发件箱 / webhook 等），
 * 经 YAML {@code type: eventPublisher} 步骤（或任意 Java 代码）调用。</p>
 *
 * <p><b>事务时机由框架保障</b>：eventPublisher 步骤检测到活动事务时注册
 * {@code TransactionSynchronization.afterCommit}，提交成功后才回调 {@link #publish}——
 * 实现方只管外发，无需（也不应）自行处理事务同步。无事务时立即发布。</p>
 *
 * <p><b>实现建议</b>：afterCommit 之后发布失败无法回滚已提交事务，实现方需自行保证
 * 可靠性（重试 / 本地发件箱），并建议幂等（消费方可能收到重复事件）。</p>
 */
public interface EventPublisher {

    /** 发布单个领域事件。event 为业务事件对象（框架不感知具体类型）。 */
    void publish(Object event);
}
