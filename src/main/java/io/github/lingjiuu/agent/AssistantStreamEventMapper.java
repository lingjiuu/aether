package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.AssistantStreamEvent;

import java.util.ArrayList;
import java.util.List;

public class AssistantStreamEventMapper {

    public List<AgentEvent> map(AssistantStreamEvent event, int turn) {
        List<AgentEvent> events = new ArrayList<>();
        if (event == null || event.getType() == null) {
            return events;
        }

        switch (event.getType()) {
            case TEXT_DELTA -> events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.ASSISTANT_TEXT_DELTA)
                    .turn(turn)
                    .delta(event.getDelta())
                    .build());
            case THINKING_DELTA -> events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.REASONING_DELTA)
                    .turn(turn)
                    .delta(event.getDelta())
                    .build());
            default -> {
            }
        }

        return events;
    }
}
