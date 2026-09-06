package com.example.datatransfer.core.spec;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据搬运合同：规则集合 + 全局配置（设计文档 §2.4 / §8.3）。
 *
 * <p>YAML 主加载路径经 {@code TransferSpecValidator} 的 JSON Schema 校验（第一道，
 * 含"拒绝未知键"强约束）；本类的 Bean Validation 注解为模型契约的文档化表达。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferSpec {

    @NotBlank
    private String version;

    @NotBlank
    private String name;

    @Valid
    private TransferOptions options = new TransferOptions();

    /** 多源合并声明（设计文档 §6.4；第一版未实现执行语义，引擎构造期 fail-fast） */
    private List<SourceDeclaration> sources;

    @NotEmpty
    private List<MappingRule> rules;

    /** 批量路径改写（设计文档 §3.3；第一版未实现执行语义，引擎构造期 fail-fast） */
    private List<PathRewrite> rewrites;

    /** 计算字段：在目标 FlatMap 上求值，表达式引用目标路径（设计文档 §6.2） */
    private List<ComputedField> computed = new ArrayList<>();

    /** 默认值：仅当目标键不存在时注入 */
    private List<DefaultValue> defaults = new ArrayList<>();

    /** 可观测性配置（第一版仅模型承载，不产生埋点） */
    private ObservabilityConfig observability;

    /** 容器空安全视图（builder 路径可能未初始化） */
    public List<ComputedField> computedOrEmpty() {
        return computed == null ? List.of() : computed;
    }

    public List<DefaultValue> defaultsOrEmpty() {
        return defaults == null ? List.of() : defaults;
    }

    public TransferOptions optionsOrNew() {
        return options == null ? new TransferOptions() : options;
    }
}
