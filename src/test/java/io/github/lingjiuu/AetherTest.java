package io.github.lingjiuu;

import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandType;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.wire.WireAdapter;
import io.github.lingjiuu.wire.WireAdapterRegistry;
import io.github.lingjiuu.wire.WireSession;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class AetherTest extends TestCase {

    public void testAetherFacadeSubmitsCommandsAndStreamsEventsAcrossSessionSwitches() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-facade-test");
        List<UiEvent> events = new CopyOnWriteArrayList<>();
        try (Aether aether = new Aether(new SessionFactory(sessionConfig(tempDir)), events::add, null)) {
            String firstSessionId = aether.sessionId();
            assertNotNull(firstSessionId);

            UiCommand firstCommand = UiCommand.submitUserInput("hello");
            var firstAck = aether.submit(firstCommand);
            assertTrue(firstAck.accepted());
            assertEquals(firstCommand.getCommandId(), firstAck.commandId());
            aether.waitForIdle();

            assertEquals(1, aether.history().turns().size());
            assertEquals(firstCommand.getCommandId(), aether.history().turns().getFirst().commandId());
            assertTrue(hasEvent(events, firstSessionId, UiEventType.TURN_COMPLETED));
            assertFalse(aether.eventsAfter(0).isEmpty());

            assertTrue(aether.submit(UiCommand.simple(UiCommandType.NEW_SESSION)).accepted());
            String secondSessionId = aether.sessionId();
            assertFalse(firstSessionId.equals(secondSessionId));

            UiCommand secondCommand = UiCommand.submitUserInput("again");
            assertTrue(aether.submit(secondCommand).accepted());
            aether.waitForIdle();

            assertTrue(hasEvent(events, secondSessionId, UiEventType.TURN_COMPLETED));
            assertEquals(secondCommand.getCommandId(), aether.history().turns().getFirst().commandId());
        }
    }

    private boolean hasEvent(List<UiEvent> events, String sessionId, UiEventType type) {
        return events.stream()
                .anyMatch(event -> event.getType() == type && sessionId.equals(event.getSessionId()));
    }

    private SessionConfig sessionConfig(Path cwd) {
        return new SessionConfig(
                new ModelClient(new WireAdapterRegistry().register(new NoopProvider())),
                "You are a test agent.",
                "",
                "",
                List.of(),
                cwd.toAbsolutePath().normalize(),
                TestModelSelections.fakeSelection(),
                new TranscriptStore(cwd.resolve("transcripts")),
                List.of(),
                List.of()
        );
    }

    private static final class NoopProvider implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> new AssistantStream() {
                @Override
                public io.github.lingjiuu.message.AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
                    return result();
                }

                @Override
                public io.github.lingjiuu.message.AssistantMessage result() {
                    return io.github.lingjiuu.message.AssistantMessage.builder()
                            .stopReason(io.github.lingjiuu.message.AssistantMessage.StopReason.STOP)
                            .build();
                }
            };
        }
    }
}
