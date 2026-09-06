package com.example.myapp.framework.core;

/**
 * 数据转换步骤：读取 payload（和 #vars），产出新的 payload。
 *
 * <p>纯映射可用内置 SpEL 实现；复杂转换建议实现本接口为 Spring Bean，经 {@code ref} 引用，
 * 这样既保持配置驱动，又让复杂逻辑可单测、可用 Java 语言表达。
 */
public interface DataTransformer extends Step {
}
