package com.example.datatransfer.core.exception;

/** 引擎构造期（装配期）错误：spec 结构校验失败、未支持特性（sources/rewrites）、通配数量不一致、strictMode 字面目标重复等。 */
public class TransferAssemblyException extends TransferException {

    public TransferAssemblyException(String message) {
        super(message);
    }
}
