package io.github.lingjiuu.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {

    private String name;

    private String description;

    private Path location;

    private boolean disableModelInvocation;
}
