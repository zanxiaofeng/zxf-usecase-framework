package com.example.myapp.domain.event;

/**
 * 用户画像快照已创建（业务事实）。
 *
 * @param snapshotId 快照标识
 * @param userId     归属用户
 */
public record SnapshotCreatedEvent(String snapshotId, String userId) implements DomainEvent {

    /** 供 SpEL 表达式 T(...).of(...) 使用 */
    public static SnapshotCreatedEvent of(String snapshotId, String userId) {
        return new SnapshotCreatedEvent(snapshotId, userId);
    }
}
