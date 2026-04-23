package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.AssistantStreamEvent;
import junit.framework.TestCase;

import java.util.List;

public class AssistantStreamEventMapperTest extends TestCase {

    public void testMapperConvertsTextAndThinkingDeltasToAgentEvents() {
        AssistantStreamEventMapper mapper = new AssistantStreamEventMapper();

        List<AgentEvent> textEvents = mapper.map(AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                .delta("Hello")
                .build(), 2);
        List<AgentEvent> thinkingEvents = mapper.map(AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.THINKING_DELTA)
                .delta("Thinking")
                .build(), 2);

        assertEquals(1, textEvents.size());
        assertEquals(AgentEvent.Type.ASSISTANT_TEXT_DELTA, textEvents.getFirst().getType());
        assertEquals("Hello", textEvents.getFirst().getDelta());
        assertEquals(Integer.valueOf(2), textEvents.getFirst().getTurn());

        assertEquals(1, thinkingEvents.size());
        assertEquals(AgentEvent.Type.REASONING_DELTA, thinkingEvents.getFirst().getType());
        assertEquals("Thinking", thinkingEvents.getFirst().getDelta());
        assertEquals(Integer.valueOf(2), thinkingEvents.getFirst().getTurn());

        assertTrue(mapper.map(AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.TOOLCALL_DELTA)
                .delta("{}")
                .build(), 2).isEmpty());
    }
}
