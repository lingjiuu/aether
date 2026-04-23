package io.github.lingjiuu.session;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class AgentSessionLiveSmokeTest extends TestCase {

    public void testSessionPromptUsesRealNetwork() {
        AgentSessionFactory factory = AgentSessionFactory.createDefault();
        AgentSession session = factory.openSession();
        List<AgentSessionEvent.Type> eventTypes = new ArrayList<>();
        session.subscribe(event -> eventTypes.add(event.getType()));

        String provider = factory.configuration().getModel().getProvider();
        String model = factory.configuration().getModel().getId();
        System.out.println("=== Live session smoke test ===");
        System.out.println("provider=" + provider + ", model=" + model);

        session.prompt("请用一句简短中文自我介绍，不要使用工具。");

        assertFalse("Session should record messages after a live prompt.", session.messages().isEmpty());
        Message lastMessage = session.messages().getLast();
        assertEquals("Live session should end on an assistant message.", Message.Role.ASSISTANT, lastMessage.role());

        AssistantMessage assistantMessage = (AssistantMessage) lastMessage;
        assertTrue(
                "Live session should finish with a successful assistant stop reason.",
                assistantMessage.getStopReason() == AssistantMessage.StopReason.STOP
                        || assistantMessage.getStopReason() == AssistantMessage.StopReason.LENGTH
        );

        String answer = MessageContents.text(assistantMessage);
        System.out.println(answer);

        assertFalse("Live assistant answer should not be blank.", answer.isBlank());
        assertTrue("Expected USER_MESSAGE event in live session.", eventTypes.contains(AgentSessionEvent.Type.USER_MESSAGE));
        assertTrue("Expected RUN_START event in live session.", eventTypes.contains(AgentSessionEvent.Type.RUN_START));
        assertTrue("Expected ASSISTANT_MESSAGE event in live session.", eventTypes.contains(AgentSessionEvent.Type.ASSISTANT_MESSAGE));
        assertTrue("Expected RUN_END event in live session.", eventTypes.contains(AgentSessionEvent.Type.RUN_END));
    }
}
