package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class DefaultLlmMessageConverterTest extends TestCase {

    public void testDefaultConverterFiltersNullsAndPreservesOrder() {
        DefaultLlmMessageConverter converter = new DefaultLlmMessageConverter();
        UserMessage userMessage = UserMessage.builder()
                .contents(List.of(TextContent.builder().text("Hello").build()))
                .build();
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .contents(List.of(TextContent.builder().text("Done.").build()))
                .build();

        List<Message> input = new ArrayList<>();
        input.add(userMessage);
        input.add(null);
        input.add(assistantMessage);

        List<Message> output = converter.convertToLlm(input);

        assertEquals(List.of(userMessage, assistantMessage), output);
        try {
            output.add(userMessage);
            fail("Expected immutable LLM message list");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }
}
