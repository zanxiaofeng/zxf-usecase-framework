package com.example.datatransfer.core.exception;

/**
 * Data Transfer 异常体系基类（评审 4.2）：装配期配置错误与运行期数据/执行错误
 * 分型，调用方可按子类精确捕获与分类处置（如 usecase errorMappings 映射 4xx）。
 *
 * <ul>
 *   <li>{@link TransferAssemblyException}——引擎构造期（spec 结构校验失败、未支持特性等）</li>
 *   <li>{@link RuleMatchException}——运行期规则匹配（missingPolicy=ERROR、strictMode 保留字符键）</li>
 *   <li>{@link TransformException}——运行期变换链（未知函数、函数执行失败、条件求值失败），携带规则上下文</li>
 *   <li>{@link ValidationException}——validations 校验失败（携带失败明细列表）</li>
 * </ul>
 */
public class TransferException extends RuntimeException {

    public TransferException(String message) {
        super(message);
    }

    public TransferException(String message, Throwable cause) {
        super(message, cause);
    }
}
