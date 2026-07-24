package com.papalegua.app.models;

public class Message {
    private int id;
    private int fromUserId;
    private int toUserId;
    private String text;
    private String type; // text, audio, file, image
    private String url;
    private String audioUrl;
    private String name;
    private long timestamp;
    private boolean read;
    private boolean edited;

    public Message(int id, int fromUserId, int toUserId, String text, String type, long timestamp) {
        this.id = id;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.text = text;
        this.type = type;
        this.timestamp = timestamp;
        this.read = false;
        this.edited = false;
    }

    // Getters e Setters
    public int getId() { return id; }
    public int getFromUserId() { return fromUserId; }
    public int getToUserId() { return toUserId; }
    public String getText() { return text; }
    public String getType() { return type; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getTimestamp() { return timestamp; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
}
