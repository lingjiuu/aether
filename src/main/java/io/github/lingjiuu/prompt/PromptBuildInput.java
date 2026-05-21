package io.github.lingjiuu.prompt;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.List;

public record PromptBuildInput(
        SessionConfig config,
        List<Message> messages,
        List<ToolDefinition> tools,
        List<Skill> skills
) {

    public PromptBuildInput {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
