package io.github.lingjiuu.session;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
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

    public void testGrepToolUsesRealNetwork() throws Exception {
        Path fixture = Path.of("target", "grep-live-smoke", "aether-grep-live-smoke.txt");
        Files.createDirectories(fixture.getParent());
        String secret = "AETHER_GREP_LIVE_SMOKE_24681357";
        Files.writeString(fixture, "SECRET_CODE=" + secret + "\n");

        AgentSessionFactory factory = AgentSessionFactory.createDefault();
        AgentSession session = factory.openSession();
        List<AgentSessionEvent.Type> eventTypes = new ArrayList<>();
        session.subscribe(event -> eventTypes.add(event.getType()));

        String provider = factory.configuration().getModel().getProvider();
        String model = factory.configuration().getModel().getId();
        System.out.println("=== Live grep tool smoke test ===");
        System.out.println("provider=" + provider + ", model=" + model);
        System.out.println("fixture=" + fixture);

        session.prompt("""
                请必须调用 grep 工具在文件 target/grep-live-smoke/aether-grep-live-smoke.txt 中搜索 SECRET_CODE。
                搜索后，只回答文件中 SECRET_CODE= 后面的值。
                不要猜测，不要解释，不要在未调用 grep 工具的情况下回答。
                """);

        assertTrue("Expected TOOL_CALL event in live grep smoke.", eventTypes.contains(AgentSessionEvent.Type.TOOL_CALL));
        assertTrue("Expected TOOL_RESULT event in live grep smoke.", eventTypes.contains(AgentSessionEvent.Type.TOOL_RESULT));
        ToolResultMessage toolResult = findToolResult(session.messages());
        assertNotNull("Live grep smoke should record a tool result message.", toolResult);
        assertEquals("grep", toolResult.getToolName());
        assertFalse("Live grep tool result should not be an error.", toolResult.isError());
        assertTrue("Live grep tool result should contain the fixture secret.", MessageContents.text(toolResult).contains(secret));

        Message lastMessage = session.messages().getLast();
        assertEquals("Live grep smoke should end on an assistant message.", Message.Role.ASSISTANT, lastMessage.role());
        String answer = MessageContents.text((AssistantMessage) lastMessage);
        System.out.println(answer);
        assertTrue("Live assistant answer should include the fixture secret.", answer.contains(secret));
    }

    private ToolResultMessage findToolResult(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof ToolResultMessage toolResultMessage) {
                return toolResultMessage;
            }
        }
        return null;
    }
}
