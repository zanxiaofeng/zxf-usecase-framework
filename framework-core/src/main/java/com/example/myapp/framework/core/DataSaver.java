package com.example.myapp.framework.core;

/**
 * 数据保存步骤：将 payload 持久化到出端口。
 *
 * <p>内置实现 {@code SpelDataSaverStep} 约定：表达式返回 null 时保留原 payload 不变；
 * 返回非 null 时以返回值作为新 payload（例如 save 后带生成主键的实体）。
 */
public interface DataSaver extends Step {
}
