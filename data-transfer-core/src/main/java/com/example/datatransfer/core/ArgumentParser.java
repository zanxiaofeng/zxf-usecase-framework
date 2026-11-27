package com.example.datatransfer.core;

import java.util.ArrayList;
import java.util.List;

import lombok.experimental.UtilityClass;

/**
 * 变换链与断言共用的实参文本解析：按逗号切分并剥离成对的单/双引号。
 * 第一版不支持嵌套逗号与转义序列（含逗号的字面量须自行规避）。
 */
@UtilityClass
class ArgumentParser {

    /** {@code "a, 'b c', \"d\""} → {@code [a, b c, d]}；空实参返回空列表 */
    List<String> parse(String rawArgs) {
        List<String> args = new ArrayList<>();
        for (String arg : split(rawArgs)) {
            args.add(unquote(arg));
        }
        return args;
    }

    /** 仅按逗号切分并 trim，保留引号：调用方据此区分字面量与形如 {@code $path} 的引用；空实参返回空列表 */
    List<String> split(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String arg : rawArgs.split(",")) {
            args.add(arg.trim());
        }
        return args;
    }

    /** 剥离成对的单/双引号；长度不足 2 或引号不成对时原样返回 */
    String unquote(String arg) {
        boolean single = arg.startsWith("'") && arg.endsWith("'");
        boolean doubleQuoted = arg.startsWith("\"") && arg.endsWith("\"");
        if (arg.length() >= 2 && (single || doubleQuoted)) {
            return arg.substring(1, arg.length() - 1);
        }
        return arg;
    }
}
