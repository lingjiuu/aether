package io.github.lingjiuu.skill;

import java.nio.file.Path;

public record SkillInjection(
        String name,
        Path path,
        String contents
) {
}
