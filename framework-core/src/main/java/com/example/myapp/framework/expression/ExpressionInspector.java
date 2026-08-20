package com.example.myapp.framework.expression;

import java.util.LinkedHashSet;
import java.util.Set;

import lombok.experimental.UtilityClass;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.common.CompositeStringExpression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 表达式静态分析（启动期数据流报告用）：收集表达式/模板对数据根的读取首段。
 *
 * <p>纯静态分析、不执行表达式：输入为配置原文，输出如 {@code vars.credit} / {@code biz.businessId} 的读取集合。
 * 识别 {@code #vars.x}（变量形式）与 {@code #{vars.x}}（根对象属性形式）两种写法；
 * 动态键（{@code #vars[k]}）只记录根。表达式语法错误时跳过该条（报告是建议性输出，不影响装配）。</p>
 *
 * <p>启动期一次性调用，自带独立解析器（不复用 {@link StepExpressionEvaluator} 的请求期缓存）。</p>
 */
@UtilityClass
public class ExpressionInspector {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final TemplateParserContext TEMPLATE_CONTEXT = new TemplateParserContext();

    /** 数据根（与 StepExpressionEvaluator 注册的变量 / StepContext 根对象属性对应） */
    private static final Set<String> DATA_ROOTS = Set.of("vars", "biz", "payload", "body");

    /**
     * 收集一条配置文本（字面量 / 完整 SpEL / #{...} 模板）中的数据根读取。
     * 判定规则与 {@link StepExpressionEvaluator#resolve} 保持一致：非表达式字面量返回空集。
     */
    public Set<String> collectReads(String expressionOrTemplate) {
        if (expressionOrTemplate == null) {
            return Set.of();
        }
        boolean template = expressionOrTemplate.contains("#{");
        String trimmed = expressionOrTemplate.strip();
        if (!template && !trimmed.startsWith("#") && !trimmed.startsWith("@")
                && !trimmed.startsWith("T(") && !trimmed.startsWith("T (")) {
            return Set.of();
        }
        Expression parsed;
        try {
            parsed = template
                    ? PARSER.parseExpression(expressionOrTemplate, TEMPLATE_CONTEXT)
                    : PARSER.parseExpression(trimmed);
        } catch (ParseException e) {
            return Set.of();
        }
        Set<String> reads = new LinkedHashSet<>();
        collect(parsed, reads);
        return reads;
    }

    private void collect(Expression expression, Set<String> reads) {
        if (expression instanceof CompositeStringExpression composite) {
            for (Expression part : composite.getExpressions()) {
                collect(part, reads);
            }
            return;
        }
        if (expression instanceof SpelExpression spel && spel.getAST() != null) {
            walk(spel.getAST(), null, reads);
        }
    }

    private void walk(SpelNode node, SpelNode parent, Set<String> reads) {
        if (node instanceof VariableReference variable) {
            String root = variable.toStringAST();
            if (root.startsWith("#")) {
                root = root.substring(1);
            }
            if (DATA_ROOTS.contains(root)) {
                reads.add(root + firstPropertySegment(parent, node));
            }
        }
        // 根对象属性形式（#{vars.credit} 模板内）：CompoundExpression 首段是 PropertyOrFieldReference
        if (node instanceof PropertyOrFieldReference property && parent instanceof CompoundExpression
                && parent.getChild(0) == node && DATA_ROOTS.contains(property.toStringAST())) {
            reads.add(property.toStringAST() + firstPropertySegment(parent, node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), node, reads);
        }
    }

    /** 复合表达式中根节点之后的第一个属性段（#vars.credit.score → ".credit"；索引器静态键同理；取不到则为空） */
    private String firstPropertySegment(SpelNode parent, SpelNode rootNode) {
        if (!(parent instanceof CompoundExpression compound)) {
            return "";
        }
        for (int i = 0; i < compound.getChildCount() - 1; i++) {
            if (compound.getChild(i) != rootNode) {
                continue;
            }
            SpelNode next = compound.getChild(i + 1);
            if (next instanceof PropertyOrFieldReference property) {
                return "." + property.toStringAST();
            }
            if (next instanceof Indexer indexer) {
                String key = indexer.toStringAST();   // 形如 ['credit']
                if (key.length() > 4 && key.startsWith("['") && key.endsWith("']")) {
                    return "." + key.substring(2, key.length() - 2);
                }
            }
            return "";
        }
        return "";
    }
}
