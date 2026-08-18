package com.example.myapp.framework.expression;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Map;

/**
 * 宽容的 Map 属性访问器：Map 上任意 key 的属性读取都认领，缺失 key 返回 null 而非抛 EL1008E。
 *
 * <p>Spring Framework 6.1+ 的 {@code MapAccessor} 为支持表达式编译，{@code canRead} 要求
 * key 已存在于 Map 中——缺失 key 会落到反射访问器并抛出 EL1008E。配置驱动场景下，
 * validator / starter 经常探测可选字段（如 {@code #body.reason != null}），
 * 缺失即 null 的宽容语义更符合直觉，故注册在 Spring 内置访问器之前。</p>
 *
 * <p>写入不接管（返回 false），交由默认访问器处理。</p>
 */
final class LenientMapAccessor implements PropertyAccessor {

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[]{Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target instanceof Map;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) {
        return new TypedValue(((Map<?, ?>) target).get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
        throw new AccessException("map writes are not supported by LenientMapAccessor");
    }
}
