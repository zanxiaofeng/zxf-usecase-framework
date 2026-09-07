package com.example.datatransfer.core.exception;

/** 运行期规则匹配错误：missingPolicy=ERROR 时规则未命中、strictMode 下源键含保留字符转义记法（不可映射）。 */
public class RuleMatchException extends TransferException {

    public RuleMatchException(String message) {
        super(message);
    }
}
