package com.example.myapp.domain.model;

public class User {
    private final UserId id;
    private final String name;

    public User(UserId id, String name) {
        this.id = id;
        this.name = name;
    }

    public UserId getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
