package io.github.lingjiuu.compact.toolbudget;

import io.github.lingjiuu.agent.turn.pipeline.PreModelStepResult;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import junit.framework.TestCase;

import java.util.List;

public class ToolResultBudgetStepTest extends TestCase {

    public void testReplacesLargeToolResultsAndEmitsContentReplacementMarker() {
        ToolResultBudgetStep step = new ToolResultBudgetStep(new ToolResultBudgetPolicy(40, 12));
        ToolResultMessage first = toolResult("call-1", "abcdefghijklmnopqrstuvwxyz");
        ToolResultMessage second = toolResult("call-2", "012345678901234567890123456789");

        PreModelStepResult result = step.apply(null, List.of(
                AssistantMessage.builder()
                        .contents(List.of(TextContent.builder().text("calling tools").build()))
                        .build(),
                first,
                second
        ));

        assertEquals(1, result.recordedMessages().size());
        assertTrue(result.recordedMessages().getFirst() instanceof SystemMessage);
        SystemMessage marker = (SystemMessage) result.recordedMessages().getFirst();
        assertEquals(SystemMessage.Subtype.CONTENT_REPLACEMENT, marker.getSubtype());
        assertFalse(marker.getToolResultReplacements().isEmpty());

        List<String> modelTexts = result.messages().stream()
                .filter(message -> message instanceof ToolResultMessage)
                .map(MessageContents::text)
                .toList();
        assertTrue(modelTexts.stream().anyMatch(text -> text.startsWith("[Aether tool result stored outside active context]")));
    }

    private ToolResultMessage toolResult(String callId, String text) {
        return ToolResultMessage.builder()
                .toolCallId(callId)
                .toolName("test_tool")
                .contents(List.of(TextContent.builder().text(text).build()))
                .build();
    }
}
