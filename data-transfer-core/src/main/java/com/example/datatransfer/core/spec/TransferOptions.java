package com.example.datatransfer.core.spec;

import lombok.Data;

/** TransferSpec 全局选项（设计文档 §8.3）。YAML 缺省时经字段初始化器生效。 */
@Data
public class TransferOptions {

    /** FlatKey 分隔符，限定单字符（Schema maxLength:1） */
    private String separator = ".";

    /** 数组通配符（第一版仅支持 [*]） */
    private String arrayWildcard = "[*]";

    private NullPolicy nullPolicy = NullPolicy.SKIP;

    private MissingPolicy missingPolicy = MissingPolicy.WARN;

    /** true 时装配后首次 transfer 前对含保留字符转义记法的源键 fail-fast（设计文档 §5.3-2） */
    private boolean strictMode = false;

    /** validations 执行模式（评审 6.3）：FAIL_FAST 首个失败即抛 / COLLECT 收集全部失败 */
    private ValidationMode validationMode = ValidationMode.FAIL_FAST;
}
