package com.example.myapp.framework.core.dataflow;

/**
 * 用例的数据链录制配置（装配期注入 {@code UseCase}）。
 *
 * @param recordEnabled 是否启用运行期录制（{@code usecase.dataflow.record}）
 * @param book          声明簿（含本用例及传递子用例的声明）；owner 录制器 {@code finish()} 时用于对照检查
 */
public record DataflowOptions(boolean recordEnabled, DataflowBook book) {

    /** 关闭录制（默认；测试与手工装配的便捷值） */
    public static DataflowOptions disabled() {
        return new DataflowOptions(false, new DataflowBook());
    }

    /** 启用录制并携带声明簿 */
    public static DataflowOptions recording(DataflowBook book) {
        return new DataflowOptions(true, book);
    }
}
