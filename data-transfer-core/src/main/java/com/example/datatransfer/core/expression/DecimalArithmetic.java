package com.example.datatransfer.core.expression;

import java.math.BigDecimal;
import java.math.MathContext;

import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlOperator;
import org.jspecify.annotations.Nullable;

/**
 * 十进制算术（外部评审二轮 P1-5）：JEXL 把无后缀的小数字面量解析为 {@code Double}
 * （{@code 0.1 + 0.2 = 0.30000000000000004}），对「浮点形态」的字符串操作数（含小数点或指数，
 * 如上游以字符串传递的金额 {@code "8.20"}）也走 double 路径。本类在四则运算与比较前把这些操作数
 * 统一提升为 {@link BigDecimal}——{@code Double.toString} 与十进制字符串都是其精确十进制表示。
 *
 * <p>数值契约：</p>
 * <ul>
 *   <li>JEXL 的操作数分类不变：整数之间保持 Java 语义（{@code 10 / 4 = 2} 整除，除零抛错），
 *       纯数字字符串同样按整数处理；只有原本会走 double 路径的操作数被提升；</li>
 *   <li>涉及小数的运算按 {@link MathContext#DECIMAL128}（34 位有效数字，HALF_EVEN）；</li>
 *   <li>小数结果不窄化为整数（{@code 113.0 * 100 = 11300.0}），整数结果维持 JEXL 的 Integer / Long / BigInteger 窄化；</li>
 *   <li>{@code +} 遇到字符串仍是拼接（JEXL 语义）：{@code '010' + '123'} 不能变成算术，
 *       所以加法入口只提升 {@code Double}，不提升字符串。</li>
 * </ul>
 */
final class DecimalArithmetic extends JexlArithmetic {

    DecimalArithmetic() {
        super(true, MathContext.DECIMAL128, -1);
    }

    /**
     * JEXL 在求值期按线程级选项重建算术实例时使用的构造器（见 JexlArithmetic#createWithOptions）。
     * 须为 public：JEXL 只按 {@code getConstructor(boolean, MathContext, int)} 查找 public 构造器，
     * 找不到时会退化为普通 JexlArithmetic 而悄悄丢失十进制语义。
     */
    public DecimalArithmetic(boolean strict, MathContext mathContext, int mathScale) {
        super(strict, mathContext, mathScale);
    }

    @Override
    public Object add(Object left, Object right) {
        return super.add(literal(left), literal(right));
    }

    @Override
    public Object subtract(Object left, Object right) {
        return super.subtract(decimal(left), decimal(right));
    }

    @Override
    public Object multiply(Object left, Object right) {
        return super.multiply(decimal(left), decimal(right));
    }

    @Override
    public Object divide(Object left, Object right) {
        return super.divide(decimal(left), decimal(right));
    }

    @Override
    public Object mod(Object left, Object right) {
        return super.mod(decimal(left), decimal(right));
    }

    @Override
    public Object negate(Object value) {
        return super.negate(decimal(value));
    }

    @Override
    public boolean equals(Object left, Object right) {
        return super.equals(decimal(left), decimal(right));
    }

    @Override
    public boolean lessThan(Object left, Object right) {
        return super.lessThan(decimal(left), decimal(right));
    }

    @Override
    public boolean lessThanOrEqual(Object left, Object right) {
        return super.lessThanOrEqual(decimal(left), decimal(right));
    }

    @Override
    public boolean greaterThan(Object left, Object right) {
        return super.greaterThan(decimal(left), decimal(right));
    }

    @Override
    public boolean greaterThanOrEqual(Object left, Object right) {
        return super.greaterThanOrEqual(decimal(left), decimal(right));
    }

    @Override
    protected int compare(Object left, Object right, JexlOperator operator) {
        return super.compare(decimal(left), decimal(right), operator);
    }

    /** 小数结果保持 BigDecimal（JEXL 默认会把 5.0 这类整数值结果窄化为 Integer，与历史输出形态 {@code 5.0} 不一致） */
    @Override
    protected Number narrowBigDecimal(Object lhs, Object rhs, BigDecimal result) {
        return result;
    }

    /** Double 字面量与「浮点形态」的字符串（JEXL 原本对二者都走 double 路径）→ BigDecimal；其余操作数原样 */
    private Object decimal(Object value) {
        if (value instanceof Double number) {
            return BigDecimal.valueOf(number);
        }
        if (value instanceof CharSequence && isFloatingPointNumber(value)) {
            BigDecimal decimal = parseDecimal(value.toString());
            return decimal == null ? value : decimal;
        }
        return value;
    }

    /** 加法入口只提升 Double 字面量：字符串参与 {@code +} 仍是拼接 */
    private static Object literal(Object value) {
        return value instanceof Double number ? BigDecimal.valueOf(number) : value;
    }

    private static @Nullable BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notDecimal) {
            return null;   // 形态匹配 FLOAT_PATTERN 却不可解析（如 "1e"）：保持字符串，交 JEXL 原路径处理
        }
    }
}
