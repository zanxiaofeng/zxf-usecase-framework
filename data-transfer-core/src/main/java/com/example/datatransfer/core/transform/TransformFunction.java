package com.example.datatransfer.core.transform;

import java.util.List;
import java.util.Map;

/**
 * 变换函数扩展接口（设计文档 §8.5）：单值变换，链式调用中的每一段。
 *
 * <p>自定义函数经 {@code TransferEngine} 构造器的 {@code extraFunctions} 注册，
 * 配置中直接以函数名引用（第一版不实现 {@code fn:} 前缀语法）。</p>
 */
@FunctionalInterface
public interface TransformFunction {

    /**
     * @param value   当前值（链中上一段输出；可能为 null，default() 据此兜底）
     * @param args    函数实参（已剥离引号；无参为空列表）
     * @param context 源 FlatMap（顶层键即源数据根字段）
     * @return 变换结果（写入目标 FlatKey 的值）
     */
    Object apply(Object value, List<String> args, Map<String, Object> context);
}
