package com.example.myapp.framework.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管道执行上下文，在 step 之间流转。
 *
 * <ul>
 *   <li>{@code payload} —— 步骤间传递的「主数据」，逐段被加载、转换、保存</li>
 *   <li>{@code vars} —— 命名旁路结果（如 httpRequester 配置 {@code as: credit}），SpEL 中经 {@code #vars.credit} 引用</li>
 *   <li>{@code biz} —— 关键数据区：starter step 在用例开始时提取的 businessId / traceId / 租户等业务标识，
 *       SpEL 中经 {@code #biz.xxx} 引用，并同步到日志 MDC（{@code biz.*}）便于全链路日志关联</li>
 *   <li>{@code request} —— 入站请求抽象，SpEL 中经 {@code #path / #query / #headers / #body} 引用</li>
 * </ul>
 */
public final class StepContext {

    private final ExchangeRequest request;
    private Object payload;
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final Map<String, Object> biz = new LinkedHashMap<>();

    public StepContext(ExchangeRequest request) {
        this.request = request;
    }

    public ExchangeRequest getRequest() {
        return request;
    }

    // ------------------------------------------------------------------
    // 便捷访问器：SpEL 模板表达式（#{path.id} / #{vars.x} / #{body.name}）以 StepContext 为根对象
    // ------------------------------------------------------------------

    public Map<String, String> getPath() {
        return request.pathVariables();
    }

    public Map<String, String> getQuery() {
        return request.queryParams();
    }

    public Map<String, String> getHeaders() {
        return request.headers();
    }

    public Object getBody() {
        return request.body();
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    @SuppressWarnings("unchecked")
    public <T> T getPayload(Class<T> type) {
        return (T) payload;
    }

    public Map<String, Object> getVars() {
        return vars;
    }

    public void putVar(String name, Object value) {
        vars.put(name, value);
    }

    public Object getVar(String name) {
        return vars.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getVar(String name, Class<T> type) {
        return (T) vars.get(name);
    }

    // ------------------------------------------------------------------
    // 关键数据区（biz）：starter step 写入，SpEL 经 #biz.xxx / 模板经 #{biz.xxx} 引用
    // ------------------------------------------------------------------

    public Map<String, Object> getBiz() {
        return biz;
    }

    public void putBiz(String key, Object value) {
        biz.put(key, value);
    }

    public Object getBiz(String key) {
        return biz.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getBiz(String key, Class<T> type) {
        return (T) biz.get(key);
    }
}
