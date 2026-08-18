package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.StepContext;

/**
 * step 结果落地规则（所有内置 step 一致）：
 * <ul>
 *   <li>配置 {@code as} → 写入 {@code #vars[as]}，payload 保持不变（旁路数据）；</li>
 *   <li>未配置 {@code as} → 写入 payload；{@code overwritePayloadWithNull=false} 时 null 不覆盖。</li>
 * </ul>
 */
final class StepResultStore {

    private StepResultStore() {
    }

    static void store(StepContext context, Object value, String as, boolean overwritePayloadWithNull) {
        if (as != null) {
            context.putVar(as, value);
            return;
        }
        if (value != null || overwritePayloadWithNull) {
            context.setPayload(value);
        }
    }
}
