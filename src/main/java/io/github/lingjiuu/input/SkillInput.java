package io.github.lingjiuu.input;

import java.nio.file.Path;

public record SkillInput(String name, Path path) implements InputItem {

    public SkillInput {
        if ((name == null || name.isBlank()) && path == null) {
            throw new IllegalArgumentException("skill input must include a name or path");
        }
    }

    @Override
    public Kind kind() {
        return Kind.SKILL;
    }
}
