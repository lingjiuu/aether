package io.github.lingjiuu.tool;

public final class ToolSourceInfo {

    private final Type type;
    private final String name;

    private ToolSourceInfo(Type type, String name) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.name = name;
    }

    public static ToolSourceInfo builtin() {
        return new ToolSourceInfo(Type.BUILTIN, "builtin");
    }

    public static ToolSourceInfo custom() {
        return new ToolSourceInfo(Type.CUSTOM, "custom");
    }

    public static ToolSourceInfo extension(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("extension name must not be blank");
        }
        return new ToolSourceInfo(Type.EXTENSION, name);
    }

    public Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    public enum Type {
        BUILTIN,
        CUSTOM,
        EXTENSION
    }
}
