package io.github.lingjiuu.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptResources {

    private String systemPrompt;

    private String appendSystemPrompt;

    @Builder.Default
    private List<ContextFile> contextFiles = new ArrayList<>();

    @Builder.Default
    private List<Skill> skills = new ArrayList<>();

    public static PromptResources empty() {
        return PromptResources.builder().build();
    }
}
