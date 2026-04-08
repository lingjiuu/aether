package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class DefaultContextTransformerTest extends TestCase {

    public void testDefaultContextTransformerRunsTransformersInOrder() {
        List<String> callOrder = new ArrayList<>();
        DefaultContextTransformer transformer = new DefaultContextTransformer(List.of(
                new RecordingTransformer("window", callOrder),
                new RecordingTransformer("enricher", callOrder),
                new RecordingTransformer("budget", callOrder)
        ));

        List<Message> input = List.of(UserMessage.builder()
                .contents(List.of(TextContent.builder().text("Hello").build()))
                .build());

        List<Message> output = transformer.transformContext(input);

        assertEquals(List.of("window", "enricher", "budget"), callOrder);
        assertEquals(input, output);
        assertNotSame(input, output);
    }

    private static final class RecordingTransformer implements ContextTransformer {
        private final String name;
        private final List<String> callOrder;

        private RecordingTransformer(String name, List<String> callOrder) {
            this.name = name;
            this.callOrder = callOrder;
        }

        @Override
        public List<Message> transformContext(List<Message> messages) {
            callOrder.add(name);
            return List.copyOf(messages);
        }
    }
}
