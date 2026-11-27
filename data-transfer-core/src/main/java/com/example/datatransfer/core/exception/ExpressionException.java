package com.example.datatransfer.core.exception;

/** 表达式运行期求值失败（computed / intermediate.expr / validations.condition 的 JEXL 求值异常），携带表达式与所属声明。 */
public class ExpressionException extends TransferException {

    private final String expression;
    private final String owner;

    public ExpressionException(String expression, String owner, Throwable cause) {
        super("expression evaluation failed at " + owner + " (expr: " + expression + "): " + cause.getMessage(),
                cause);
        this.expression = expression;
        this.owner = owner;
    }

    public String getExpression() {
        return expression;
    }

    public String getOwner() {
        return owner;
    }
}
