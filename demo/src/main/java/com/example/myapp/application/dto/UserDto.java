package com.example.myapp.application.dto;

import com.example.myapp.domain.model.User;

public record UserDto(String id, String name) {

    public static UserDto from(User user) {
        return new UserDto(user.getId().value(), user.getName());
    }
}
