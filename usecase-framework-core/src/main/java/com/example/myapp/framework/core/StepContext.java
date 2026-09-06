package com.example.myapp.framework.core;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowRecorder;
import com.example.myapp.framework.core.dataflow.RecordingMap;

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
@RequiredArgsConstructor
public final class StepContext {

    /** biz 区中 traceId 的约定键名（Web 入口与 standalone 调用共享的契约；MDC 键同名，供日志 pattern 关联） */
    public static final String TRACE_ID_KEY = "traceId";

    /** 入站请求；{@link #standalone()} 场景为 null */
    @Getter
    private final @Nullable ServerRequest request;
    /** 请求体视图（惰性解析 + 缓存）；隔离子上下文与父共享同一实例 */
    private final RequestBodyView bodyView;
    private @Nullable Object payload;
    private final Map<String, @Nullable Object> vars = new LinkedHashMap<>();
    private final Map<String, @Nullable Object> biz = new LinkedHashMap<>();
    /** 数据链录制器（{@code usecase.dataflow.record} 或测试场景挂载）；null 时不录制、行为与旧版一致 */
    private @Nullable DataflowRecorder recorder;

    /** Web 入口上下文：关联当前入站请求（由 framework.web.UseCaseRouterFactory 创建）。 */
    public static StepContext of(ServerRequest request, ObjectMapper objectMapper) {
        Assert.notNull(request, "request must not be null");
        Assert.notNull(objectMapper, "objectMapper must not be null");
        return new StepContext(request, new RequestBodyView(request, objectMapper));
    }

    /** 管道外独立调用上下文（调度任务、消息消费、普通 Service）：无入站请求。 */
    public static StepContext standalone() {
        return new StepContext(null, new RequestBodyView(null, null));
    }

    /**
     * 隔离子上下文（UseCaseInvoker.invokeIsolated 使用）：共享入站请求与请求体视图
     * （Servlet 请求体流只能消费一次，body 缓存随之共享），vars / payload 全新，biz 由调用方拷贝继承；
     * 录制器随上下文传递（子链事件并入同一 trace）。
     */
    public StepContext newChildContext() {
        StepContext child = new StepContext(request, bodyView);
        child.recorder = recorder;
        return child;
    }

    // ------------------------------------------------------------------
    // 数据链录制（core.dataflow）：挂载后所有通道访问被记录为键级事件（只记键名不记值）
    // ------------------------------------------------------------------

    /** 挂载数据链录制器（重复挂载以最后一次为准） */
    public void attach(DataflowRecorder recorder) {
        Assert.notNull(recorder, "recorder must not be null");
        this.recorder = recorder;
    }

    /** 当前录制器；未挂载时为 null */
    public @Nullable DataflowRecorder recorder() {
        return recorder;
    }

    /** 摘除录制器（录制通道访问恢复为普通读写） */
    public void detach() {
        this.recorder = null;
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
    public <T> @Nullable T getPayload(Class<T> type) {
        return type.cast(getPayload());
    }

    /** 当前主数据（录制期记一次 payload 读取） */
    public @Nullable Object getPayload() {
        if (recorder != null) {
            recorder.recordRead(DataflowKey.payload());
        }
        return payload;
    }

    /** 覆盖主数据（录制期记一次 payload 写入，含类型名） */
    public void setPayload(@Nullable Object value) {
        if (recorder != null) {
            recorder.recordPayloadWrite(value == null ? "null" : value.getClass().getSimpleName());
        }
        this.payload = value;
    }

    /** vars 旁路区视图：录制期返回记录读写的传递型视图，否则返回内部 Map 本体 */
    public Map<String, @Nullable Object> getVars() {
        return recorder == null ? vars : new RecordingMap(vars, DataflowKey.Channel.VARS, recorder);
    }

    /** biz 关键数据区视图：录制期返回记录读写的传递型视图，否则返回内部 Map 本体 */
    public Map<String, @Nullable Object> getBiz() {
        return recorder == null ? biz : new RecordingMap(biz, DataflowKey.Channel.BIZ, recorder);
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

    public void putVar(String name, @Nullable Object value) {
        if (recorder != null) {
            recorder.recordWrite(DataflowKey.vars(name));
        }
        vars.put(name, value);
    }

    public @Nullable Object getVar(String name) {
        if (recorder != null) {
            recorder.recordRead(DataflowKey.vars(name));
        }
        return vars.get(name);
    }

    public <T> @Nullable T getVar(String name, Class<T> type) {
        return type.cast(getVar(name));
    }

    // ------------------------------------------------------------------
    // 关键数据区（biz）：starter step 写入，SpEL 经 #biz.xxx / 模板经 #{biz.xxx} 引用
    // ------------------------------------------------------------------

    public void putBiz(String key, @Nullable Object value) {
        if (recorder != null) {
            recorder.recordWrite(DataflowKey.biz(key));
        }
        biz.put(key, value);
    }

    public @Nullable Object getBiz(String key) {
        if (recorder != null) {
            recorder.recordRead(DataflowKey.biz(key));
        }
        return biz.get(key);
    }

    public <T> @Nullable T getBiz(String key, Class<T> type) {
        return type.cast(getBiz(key));
    }
}
