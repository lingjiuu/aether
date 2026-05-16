package io.github.lingjiuu.session;

import io.github.lingjiuu.resource.ContextFile;
import io.github.lingjiuu.resource.Skill;
import io.github.lingjiuu.tool.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemPromptBuildOptions {

    private String customPrompt;

    private String appendPrompt;

    private Path cwd;

    private LocalDate currentDate;

    @Builder.Default
    private List<ToolDefinition> activeTools = new ArrayList<>();

    @Builder.Default
    private List<ContextFile> contextFiles = new ArrayList<>();

    @Builder.Default
    private List<Skill> skills = new ArrayList<>();
}
