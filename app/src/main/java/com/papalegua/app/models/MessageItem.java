package com.papalegua.app.models;

public class MessageItem {
    public int id, fromUserId, toUserId;
    public String text, type, url, audioUrl, name;
    public long timestamp;
    public boolean read, edited;
}
