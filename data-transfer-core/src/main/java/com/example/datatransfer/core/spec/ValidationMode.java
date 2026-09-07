package com.example.datatransfer.core.spec;

/** validations 执行模式（评审 6.3）：FAIL_FAST 首个失败即抛（入参校验）/ COLLECT 收集全部失败（数据迁移）。 */
public enum ValidationMode {
    FAIL_FAST, COLLECT
}
