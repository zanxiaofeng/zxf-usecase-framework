package com.example.myapp.framework.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.myapp.framework.core.EventPublisher;

/**
 * 测试用事件发布探针：实现 {@link EventPublisher} 出端口，记录全部已发布事件供断言。
 *
 * <p>Spring 场景用法：在 {@code @TestConfiguration} 中注册为 {@code @Primary} Bean，
 * eventPublisher 步骤的运行期解析（{@code getIfAvailable}，尊重 {@code @Primary}）即命中探针，
 * 真实出站适配器在测试上下文被旁路；随后把同一实例传给
 * {@link UseCaseScenario#recordingEventsTo(RecordingEventPublisher)} 做事件断言。</p>
 *
 * <p>非 Spring 场景：直接 {@code new} 并交给装配 eventPublisher 步骤的 BeanFactory。</p>
 */
public final class RecordingEventPublisher implements EventPublisher {

    private final List<Object> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(Object event) {
        published.add(event);
    }

    /** 已发布事件（快照视图） */
    public List<Object> published() {
        return List.copyOf(published);
    }

    /** 已发布事件中指定类型的子集（快照视图） */
    public <T> List<T> published(Class<T> eventType) {
        return published.stream().filter(eventType::isInstance).map(eventType::cast).toList();
    }

    /** 清空记录（多场景复用同一探针时在场景间调用） */
    public void clear() {
        published.clear();
    }
}
