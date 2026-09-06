package com.example.myapp.framework.core.dataflow;

import org.jspecify.annotations.Nullable;

/**
 * 一次键级访问事件（实然记录的最小单元）。
 *
 * @param sequence    递增序号（同一 recorder 内单调）
 * @param useCaseId   事件归属用例（当前 step 窗口所属）
 * @param stepName    事件归属步骤
 * @param op          读或写
 * @param key         访问的数据键
 * @param payloadType payload 写入时的类型简单名（{@code null} 表示非 payload 写；隐私基线：永不携带值）
 */
public record AccessEvent(int sequence, String useCaseId, String stepName, Op op, DataflowKey key,
                          @Nullable String payloadType) {

    /** 访问类型 */
    public enum Op { READ, WRITE }

    /** 非 payload 写事件（payloadType 恒 null） */
    public static AccessEvent of(int sequence, String useCaseId, String stepName, Op op, DataflowKey key) {
        return new AccessEvent(sequence, useCaseId, stepName, op, key, null);
    }
}
