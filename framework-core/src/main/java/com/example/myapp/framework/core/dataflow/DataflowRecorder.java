package com.example.myapp.framework.core.dataflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * 数据链录制器：以 step 窗口栈归属读写事件，产出不可变 {@link DataflowTrace}。
 *
 * <p>线程封闭于管道执行线程（与 {@code StepContext} 一致），非线程安全。</p>
 *
 * <p>两种获取方式：</p>
 * <ul>
 *   <li>{@link #external()} —— 测试/编程接入：由调用方挂到 {@code StepContext}，
 *       只录制不跑对照检查；</li>
 *   <li>{@link #root(String, DataflowBook)} —— 框架自建（{@code usecase.dataflow.record=true}
 *       时由 root {@code UseCase.execute} 创建）：{@link #finish()} 时执行声明-实测对照检查。</li>
 * </ul>
 */
@Slf4j
public final class DataflowRecorder {

    private final @Nullable DataflowBook book;
    private final @Nullable String rootUseCaseId;
    private final List<AccessEvent> events = new ArrayList<>();
    private final Deque<Window> windows = new ArrayDeque<>();
    private int sequence;

    private record Window(String useCaseId, String stepName) {
    }

    private DataflowRecorder(@Nullable String rootUseCaseId, @Nullable DataflowBook book) {
        this.rootUseCaseId = rootUseCaseId;
        this.book = book;
    }

    /** 外部接入录制器：只录制不跑对照检查 */
    public static DataflowRecorder external() {
        return new DataflowRecorder(null, null);
    }

    /** 框架自建录制器：{@link #finish()} 时按声明簿执行对照检查 */
    public static DataflowRecorder root(String rootUseCaseId, DataflowBook book) {
        Assert.hasText(rootUseCaseId, "rootUseCaseId must not be blank");
        Assert.notNull(book, "book must not be null");
        return new DataflowRecorder(rootUseCaseId, book);
    }

    /** 开启一个 step 归属窗口（嵌套调用形成栈，事件归属最内层窗口） */
    public void beginStep(String useCaseId, String stepName) {
        windows.push(new Window(useCaseId, stepName));
    }

    /** 关闭当前 step 窗口（必须与 {@link #beginStep} 在 finally 中配对） */
    public void endStep() {
        Assert.state(!windows.isEmpty(), "endStep without matching beginStep");
        windows.pop();
    }

    /** 是否存在开启的 step 窗口（窗口外访问不产生事件） */
    public boolean active() {
        return !windows.isEmpty();
    }

    /** 记录一次键读取（窗口外调用被忽略） */
    public void recordRead(DataflowKey key) {
        record(AccessEvent.Op.READ, key, null);
    }

    /** 记录一次键写入（窗口外调用被忽略） */
    public void recordWrite(DataflowKey key) {
        record(AccessEvent.Op.WRITE, key, null);
    }

    /** 记录一次 payload 写入（带类型简单名；窗口外调用被忽略） */
    public void recordPayloadWrite(@Nullable String payloadType) {
        record(AccessEvent.Op.WRITE, DataflowKey.payload(), payloadType);
    }

    private void record(AccessEvent.Op op, DataflowKey key, @Nullable String payloadType) {
        Window window = windows.peek();
        if (window == null) {
            return;
        }
        events.add(new AccessEvent(sequence++, window.useCaseId(), window.stepName(), op, key, payloadType));
    }

    /** 当前事件数（窗口切片标记用） */
    public int eventCount() {
        return events.size();
    }

    /** 序号 ≥ fromSequence 的事件切片（dev trace 的步级增量用） */
    public List<AccessEvent> eventsSince(int fromSequence) {
        return List.copyOf(events.subList(Math.max(fromSequence, 0), events.size()));
    }

    /** 当前已录制内容的不可变快照（快照后继续录制不影响先前快照） */
    public DataflowTrace snapshot() {
        return DataflowTrace.of(List.copyOf(events));
    }

    /** root 录制器收尾：执行声明-实测对照检查并输出告警/汇总日志（external 录制器为空操作） */
    public void finish() {
        if (book == null) {
            return;
        }
        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, snapshot());
        for (DataflowConformance.Finding finding : findings) {
            if (finding.level() == DataflowConformance.Finding.Level.WARN) {
                log.warn("[{}] {}", rootUseCaseId, finding.message());
            } else {
                log.info("[{}] {}", rootUseCaseId, finding.message());
            }
        }
    }
}
