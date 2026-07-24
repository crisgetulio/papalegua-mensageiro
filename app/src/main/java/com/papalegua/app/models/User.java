package com.papalegua.app.models;

public class User {
    private int id;
    private String username;
    private String avatarUrl;
    private boolean online;

    public User(int id, String username, String avatarUrl) {
        this.id = id;
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public int getId() { return id; }
    public String getUsername() { return username; }
    public String getAvatarUrl() { return avatarUrl; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
}
