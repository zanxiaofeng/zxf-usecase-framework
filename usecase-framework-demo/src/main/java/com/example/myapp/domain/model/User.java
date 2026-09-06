package com.example.myapp.domain.model;

/**
 * 用户聚合根（demo 不可变示例）。生产行为（领域方法封装状态变更）按 architecture.md §3.1 落地。
 */
public record User(UserId id, String name) {
}
