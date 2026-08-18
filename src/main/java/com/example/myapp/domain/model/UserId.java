package com.example.myapp.domain.model;

public record UserId(String value) {
    public UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("UserId must not be blank");
        }
    }

    /** 供 SpEL 表达式 T(...UserId).of(...) 使用 */
    public static UserId of(String value) {
        return new UserId(value);
    }
}
