package com.example.myapp.domain.event;

/**
 * 领域事件标记接口：为 EventPublisher 端口提供类型安全（architecture.md §3.3）。
 * 领域层零依赖，事件只携带业务事实、不携带技术细节。
 */
public interface DomainEvent {
}
