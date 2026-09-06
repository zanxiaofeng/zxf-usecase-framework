package com.example.myapp.framework.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 步骤失败时的键级数据现场（随 {@code StepExecutionException} 附带，仅进日志、不进响应体）。
 *
 * <p>只带类型与键名、不带值——防 PII/敏感数据泄入日志。嵌套子用例多层包装时最内层现场优先
 * （离错误源最近者信息价值最高）。</p>
 */
public record DataSnapshot(String payloadType, Set<String> varsKeys, Set<String> bizKeys) {

    /** 从当前上下文截取现场（键集做防御性拷贝，不随管道后续执行变化） */
    public static DataSnapshot of(StepContext context) {
        Object payload = context.getPayload();
        return new DataSnapshot(
                payload == null ? "null" : payload.getClass().getSimpleName(),
                Collections.unmodifiableSet(new LinkedHashSet<>(context.getVars().keySet())),
                Collections.unmodifiableSet(new LinkedHashSet<>(context.getBiz().keySet())));
    }
}
