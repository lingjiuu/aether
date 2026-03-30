package io.github.lingjiuu.model;

import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.message.AgentMessage;
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
public class AgentState{

    private String systemPrompt;

    private AiModel model;

    private Reasoning reasoning;

    @Builder.Default
    private List<AgentMessage> messages = new ArrayList<>();

    @Builder.Default
    private List<AgentTool> tools = new ArrayList<>();


}
