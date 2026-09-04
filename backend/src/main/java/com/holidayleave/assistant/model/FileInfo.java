package com.holidayleave.assistant.model;

public final class FileInfo {
    private final String name;
    private final String path;
    private final boolean active;

    public FileInfo(String name, String path, boolean active) {
        this.name   = name;
        this.path   = path;
        this.active = active;
    }

    public String getName()   { return name; }
    public String getPath()   { return path; }
    public boolean isActive() { return active; }
}
