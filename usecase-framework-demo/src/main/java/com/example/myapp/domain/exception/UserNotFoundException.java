package com.example.myapp.domain.exception;

import lombok.Getter;

@Getter
public class UserNotFoundException extends DomainException {

    public static final String CODE = "USER_NOT_FOUND";

    private final String userId;

    public UserNotFoundException(String userId) {
        super(CODE, "User not found: %s".formatted(userId));
        this.userId = userId;
    }
}
