package com.example.myapp.framework.core;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * 管道执行上下文，在 step 之间流转（线程封闭于管道执行线程，非线程安全）。
 *
 * <ul>
 *   <li>{@code payload} —— 步骤间传递的「主数据」，逐段被加载、转换、保存</li>
 *   <li>{@code vars} —— 命名旁路结果（如 httpRequester 配置 {@code as: credit}），SpEL 中经 {@code #vars.credit} 引用</li>
 *   <li>{@code biz} —— 关键数据区：starter step 在用例开始时提取的 businessId / traceId / 租户等业务标识，
 *       SpEL 中经 {@code #biz.xxx} 引用，并同步到日志 MDC（{@code biz.*}）便于全链路日志关联</li>
 *   <li>{@code request} —— 入站 {@link ServerRequest}（core 模块直接依赖 spring-webmvc，无桥接层），
 *       SpEL 中经 {@code #path / #query / #headers / #body} 引用；管道外调用（{@link #standalone()}）为 null，
 *       此时各请求视图为空 Map / null</li>
 * </ul>
 */
public final class StepContext {

    /** 入站请求；{@link #standalone()} 场景为 null */
    @Getter
    private final @Nullable ServerRequest request;
    /** 请求体视图（惰性解析 + 缓存）；隔离子上下文与父共享同一实例 */
    private final RequestBodyView bodyView;
    @Getter
    @Setter
    private Object payload;
    @Getter
    private final Map<String, Object> vars = new LinkedHashMap<>();
    @Getter
    private final Map<String, Object> biz = new LinkedHashMap<>();

    private StepContext(@Nullable ServerRequest request, RequestBodyView bodyView) {
        this.request = request;
        this.bodyView = bodyView;
    }

    /** Web 入口上下文：关联当前入站请求（由 framework.web.UseCaseRouterFactory 创建）。 */
    public static StepContext of(ServerRequest request, ObjectMapper objectMapper) {
        return new StepContext(request, new RequestBodyView(request, objectMapper));
    }

    /** 管道外独立调用上下文（调度任务、消息消费、普通 Service）：无入站请求。 */
    public static StepContext standalone() {
        return new StepContext(null, new RequestBodyView(null, null));
    }

    /**
     * 隔离子上下文（UseCaseInvoker.invokeIsolated 使用）：共享入站请求与请求体视图
     * （Servlet 请求体流只能消费一次，body 缓存随之共享），vars / payload 全新，biz 由调用方拷贝继承。
     */
    public StepContext newChildContext() {
        return new StepContext(request, bodyView);
    }

    /** 从父上下文拷贝继承 biz 关键数据区（隔离子用例调用用；拷贝后子的修改不回传父） */
    public void inheritBizFrom(StepContext parent) {
        biz.putAll(parent.biz);
    }

    // ------------------------------------------------------------------
    // 便捷访问器：SpEL 模板表达式（#{path.id} / #{vars.x} / #{body.name}）以 StepContext 为根对象；
    // 同时供 StepExpressionEvaluator 注册 #path / #query / #headers / #body 变量
    // ------------------------------------------------------------------

    public Map<String, String> getPath() {
        return request == null ? Map.of() : request.pathVariables();
    }

    public Map<String, String> getQuery() {
        return request == null ? Map.of() : request.params().toSingleValueMap();
    }

    public Map<String, String> getHeaders() {
        return request == null ? Map.of() : request.headers().asHttpHeaders().toSingleValueMap();
    }

    /**
     * 请求体：委托 {@link RequestBodyView}（惰性读取并缓存；语义与边界情况见其 Javadoc）。
     * standalone 场景恒为 null。
     */
    public @Nullable Object getBody() {
        return bodyView.getBody();
    }

    /** 类型化读取：类型不符时立即抛 ClassCastException（而非延迟到调用点） */
    public <T> T getPayload(Class<T> type) {
        return type.cast(payload);
    }

    /**
     * step 结果落地规则（所有内置 step 一致）：
     * <ul>
     *   <li>配置 {@code as} → 写入 {@code #vars[as]}，payload 保持不变（旁路数据）；</li>
     *   <li>未配置 {@code as} → 写入 payload；{@code overwritePayloadWithNull=false} 时 null 不覆盖。</li>
     * </ul>
     */
    public void storeResult(@Nullable Object value, @Nullable String as, boolean overwritePayloadWithNull) {
        if (as != null) {
            putVar(as, value);
            return;
        }
        if (value != null || overwritePayloadWithNull) {
            setPayload(value);
        }
    }

    public void putVar(String name, Object value) {
        vars.put(name, value);
    }

    public Object getVar(String name) {
        return vars.get(name);
    }

    public <T> T getVar(String name, Class<T> type) {
        return type.cast(vars.get(name));
    }

    // ------------------------------------------------------------------
    // 关键数据区（biz）：starter step 写入，SpEL 经 #biz.xxx / 模板经 #{biz.xxx} 引用
    // ------------------------------------------------------------------

    public void putBiz(String key, Object value) {
        biz.put(key, value);
    }

    public Object getBiz(String key) {
        return biz.get(key);
    }

    public <T> T getBiz(String key, Class<T> type) {
        return type.cast(biz.get(key));
    }
}
