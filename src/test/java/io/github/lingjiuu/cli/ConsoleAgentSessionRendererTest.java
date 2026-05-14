package io.github.lingjiuu.cli;

import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.session.AgentSessionEvent;
import io.github.lingjiuu.tool.builtin.ReadTool;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

public class ConsoleAgentSessionRendererTest extends TestCase {

    public void testToolEventsUseToolDisplayRenderer() {
        ReadTool readTool = new ReadTool(FileAccessPolicy.rootedAt(Path.of(".")));
        ConsoleAgentSessionRenderer renderer = new ConsoleAgentSessionRenderer(name -> readTool);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(output));
        try {
            renderer.onEvent(AgentSessionEvent.builder()
                    .type(AgentSessionEvent.Type.TOOL_CALL)
                    .toolCall(ToolCallContent.builder()
                            .toolCallId("call-1")
                            .toolName("read")
                            .argumentsJson("{\"path\":\"README.md\",\"offset\":2}")
                            .build())
                    .build());
            renderer.onEvent(AgentSessionEvent.builder()
                    .type(AgentSessionEvent.Type.TOOL_RESULT)
                    .toolResult(ToolResultMessage.builder()
                            .toolName("read")
                            .toolCallId("call-1")
                            .contents(List.of(TextContent.builder().text("line two").build()))
                            .build())
                    .build());
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString();
        assertTrue(rendered.contains("[TOOL] read README.md offset=2"));
        assertTrue(rendered.contains("[TOOL] result=line two"));
    }
}
