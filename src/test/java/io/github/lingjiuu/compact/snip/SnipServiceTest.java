package io.github.lingjiuu.compact.snip;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class SnipServiceTest extends TestCase {

    public void testSnipRemovesMiddleMessagesAndCreatesBoundary() {
        SnipService service = new SnipService(new SnipPolicy(6, 2, 2));
        List<UserMessage> messages = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            messages.add(user("message-" + i + " with enough text"));
        }

        SnipPlan plan = service.snipIfNeeded(List.copyOf(messages));

        assertTrue(plan.executed());
        assertEquals(SystemMessage.Subtype.SNIP_BOUNDARY, plan.boundaryMessage().getSubtype());
        assertTrue(plan.boundaryMessage().getRemovedMessageIds().contains(messages.get(1).id()));
        assertEquals("message-0 with enough text", MessageContents.text(plan.messages().getFirst()));
        assertEquals("message-6 with enough text", MessageContents.text(plan.messages().getLast()));
    }

    private UserMessage user(String text) {
        return UserMessage.builder()
                .contents(List.of(TextContent.builder().text(text).build()))
                .build();
    }
}
