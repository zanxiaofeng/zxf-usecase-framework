package com.example.myapp.infrastructure.adapter.out.messaging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.example.myapp.framework.core.EventPublisher;

/**
 * 演示用内存事件发布适配器：记录已发布事件供观测与测试断言。
 * 生产环境替换为真实出站适配器（KafkaEventPublisher 经 afterCommit 外发 / 事务性发件箱），端口签名不变、YAML 不变。
 */
@Component
public class InMemoryEventPublisherAdapter implements EventPublisher {

    private final List<Object> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(Object event) {
        published.add(event);
    }

    /** 已发布事件（快照视图，供测试断言） */
    public List<Object> publishedEvents() {
        return List.copyOf(published);
    }
}
