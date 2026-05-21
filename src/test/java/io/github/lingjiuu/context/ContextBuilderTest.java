package io.github.lingjiuu.context;

import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public class ContextBuilderTest extends TestCase {

    public void testFullInitialContextMessagesBuildsEnvironmentContext() {
        ContextBuilder builder = new ContextBuilder();
        InitialContextSnapshot current = snapshot("/tmp/aether", "2026-05-20", "UTC");

        List<ContextMessage> messages = builder.fullInitialContextMessages(current);

        assertEquals(1, messages.size());
        String text = MessageContents.text(messages.getFirst());
        assertTrue(text.contains("Environment context:"));
        assertTrue(text.contains("- cwd: /tmp/aether"));
        assertTrue(text.contains("- current_date: 2026-05-20"));
        assertTrue(text.contains("- timezone: UTC"));
    }

    public void testInitialContextMessagesBuildsOnlyDiff() {
        ContextBuilder builder = new ContextBuilder();
        InitialContextSnapshot previous = snapshot("/tmp/old", "2026-05-20", "UTC");
        InitialContextSnapshot current = snapshot("/tmp/new", "2026-05-20", "UTC");

        List<ContextMessage> messages = builder.initialContextMessages(previous, current);

        assertEquals(1, messages.size());
        String text = MessageContents.text(messages.getFirst());
        assertTrue(text.contains("Environment context update:"));
        assertTrue(text.contains("- cwd: /tmp/new"));
        assertFalse(text.contains("current_date"));
        assertFalse(text.contains("timezone"));
    }

    public void testInitialContextMessagesSkipsUnchangedContext() {
        ContextBuilder builder = new ContextBuilder();
        InitialContextSnapshot previous = snapshot("/tmp/aether", "2026-05-20", "UTC");
        InitialContextSnapshot current = snapshot("/tmp/aether", "2026-05-20", "UTC");

        assertTrue(builder.initialContextMessages(previous, current).isEmpty());
    }

    private InitialContextSnapshot snapshot(String cwd, String currentDate, String timezone) {
        return new InitialContextSnapshot(new EnvironmentContext(
                Path.of(cwd),
                LocalDate.parse(currentDate),
                ZoneId.of(timezone)
        ));
    }
}
