package com.example.myapp.framework.core.dataflow;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * {@code StepContext#getVars()/getBiz()} 在录制期的传递型视图：读写行为完全穿透到底层 Map，
 * 同时把访问记录为事件——单键读写记具体键；{@code toString}（logging dump 等批量快照）记通道通配读取。
 *
 * <p>已知近似：{@code keySet/values/entrySet} 视图返回底层集合，直接遍历不产生事件
 * （键级血缘的批量遍历盲区，与局部变量同级别的不可见性）。</p>
 *
 * <p>框架内部 API：仅由 {@code StepContext} 在录制期包装返回，业务代码不应直接依赖。</p>
 */
public final class RecordingMap implements Map<String, @Nullable Object> {

    private final Map<String, @Nullable Object> delegate;
    private final DataflowKey.Channel channel;
    private final DataflowRecorder recorder;
    private final DataflowKey wildcardReadKey;

    public RecordingMap(Map<String, @Nullable Object> delegate, DataflowKey.Channel channel, DataflowRecorder recorder) {
        Assert.notNull(delegate, "delegate must not be null");
        Assert.notNull(recorder, "recorder must not be null");
        this.delegate = delegate;
        this.channel = channel;
        this.recorder = recorder;
        this.wildcardReadKey = channel == DataflowKey.Channel.VARS ? DataflowKey.vars(DataflowKey.WILDCARD)
                : DataflowKey.biz(DataflowKey.WILDCARD);
    }

    private DataflowKey key(Object name) {
        return channel == DataflowKey.Channel.VARS ? DataflowKey.vars(String.valueOf(name))
                : DataflowKey.biz(String.valueOf(name));
    }

    // ---- 读：记具体键 ----

    @Override
    public @Nullable Object get(Object key) {
        recorder.recordRead(key(key));
        return delegate.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        recorder.recordRead(key(key));
        return delegate.containsKey(key);
    }

    @Override
    public @Nullable Object getOrDefault(Object key, @Nullable Object defaultValue) {
        recorder.recordRead(key(key));
        return delegate.getOrDefault(key, defaultValue);
    }

    @Override
    public String toString() {
        // 批量快照（logging dump 等）：记通道通配读取
        recorder.recordRead(wildcardReadKey);
        return delegate.toString();
    }

    // ---- 写：记写入后穿透 ----

    @Override
    public @Nullable Object put(String key, @Nullable Object value) {
        recorder.recordWrite(key(key));
        return delegate.put(key, value);
    }

    @Override
    public void putAll(Map<? extends String, ? extends @Nullable Object> map) {
        map.keySet().forEach(name -> recorder.recordWrite(key(name)));
        delegate.putAll(map);
    }

    // ---- 结构查询：不记事件 ----

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        return delegate.containsValue(value);
    }

    // ---- 批量视图：直接委托（遍历不记录，见类 Javadoc 已知近似） ----

    @Override
    public Set<String> keySet() {
        return delegate.keySet();
    }

    @Override
    public Collection<@Nullable Object> values() {
        return delegate.values();
    }

    @Override
    public Set<Entry<String, @Nullable Object>> entrySet() {
        return delegate.entrySet();
    }

    // ---- 破坏性变更的次要形态：穿透不记（框架内无此用法） ----

    @Override
    public @Nullable Object remove(Object key) {
        return delegate.remove(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super @Nullable Object> action) {
        delegate.forEach(action);
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super @Nullable Object, ? extends @Nullable Object> function) {
        delegate.replaceAll(function);
    }

    @Override
    public @Nullable Object putIfAbsent(String key, @Nullable Object value) {
        recorder.recordWrite(key(key));
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return delegate.remove(key, value);
    }

    @Override
    public boolean replace(String key, @Nullable Object oldValue, @Nullable Object newValue) {
        recorder.recordWrite(key(key));
        return delegate.replace(key, oldValue, newValue);
    }

    @Override
    public @Nullable Object replace(String key, @Nullable Object value) {
        recorder.recordWrite(key(key));
        return delegate.replace(key, value);
    }

    @Override
    public @Nullable Object computeIfAbsent(String key,
            Function<? super String, ? extends @Nullable Object> mappingFunction) {
        recorder.recordWrite(key(key));
        return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public @Nullable Object computeIfPresent(String key,
            BiFunction<? super String, ? super @Nullable Object, ? extends @Nullable Object> remappingFunction) {
        recorder.recordWrite(key(key));
        return delegate.computeIfPresent(key, remappingFunction);
    }

    @Override
    public @Nullable Object compute(String key,
            BiFunction<? super String, ? super @Nullable Object, ? extends @Nullable Object> remappingFunction) {
        recorder.recordWrite(key(key));
        return delegate.compute(key, remappingFunction);
    }

    @Override
    public @Nullable Object merge(String key, @Nullable Object value,
            BiFunction<? super @Nullable Object, ? super @Nullable Object, ? extends @Nullable Object> remappingFunction) {
        recorder.recordWrite(key(key));
        return delegate.merge(key, value, remappingFunction);
    }
}
