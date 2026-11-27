package com.example.datatransfer.core.spec;

/**
 * null 值处置策略（设计文档 §2.4），作用于规则映射与 intermediate 的 from 形态：
 * <ul>
 *   <li>{@code SKIP}：源值为 null 时跳过，不产出目标键——变换链不执行（{@code default()} 也不会运行），
 *       目标键留给 {@code defaults} 段兜底；</li>
 *   <li>{@code KEEP}：null 是合法值，原样写入目标；{@code defaults} 段不覆盖已存在的键（即使值是 null）；</li>
 *   <li>{@code DEFAULT}：null 进入变换链（{@code default()} 可兜底）；链后仍为 null 时不写入，
 *       交给 {@code defaults} 段兜底——与 {@code KEEP} 的区别是残存的 null 不进入输出。</li>
 * </ul>
 * 键缺失（规则未命中）不属于 null：任何策略下都不执行变换链，由 {@code missingPolicy} 处置。
 */
public enum NullPolicy {
    SKIP, KEEP, DEFAULT
}
