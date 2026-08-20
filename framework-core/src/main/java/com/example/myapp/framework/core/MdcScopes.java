package com.example.myapp.framework.core;

import java.util.Map;
import java.util.function.Supplier;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/**
 * MDC 作用域工具：子上下文执行（isolate 子用例 / standalone 调用）的出入口快照并恢复 MDC。
 *
 * <p>biz 关键数据区在隔离边界是 Map 拷贝继承，但 MDC 是线程级单例——子管道内 starter 写入的
 * {@code biz.*} 不回滚会污染父管道后续日志（{@code %X{biz.businessId}} 输出子用例的值）。
 * 执行期间子的 MDC 写入正常生效（子范围内日志正确），返回时恢复父现场。</p>
 */
@UtilityClass
public class MdcScopes {

    /** 执行 action，返回前恢复进入时的 MDC 现场（进入时为空则执行后彻底清空） */
    public <T> T withRestoration(Supplier<T> action) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        try {
            return action.get();
        } finally {
            MDC.clear();
            if (snapshot != null) {
                MDC.setContextMap(snapshot);
            }
        }
    }
}
