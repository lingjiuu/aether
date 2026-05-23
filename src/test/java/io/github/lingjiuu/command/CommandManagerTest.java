package io.github.lingjiuu.command;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnId;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiSessionSummary;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.resource.PromptResources;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class CommandManagerTest extends TestCase {

    public void testSessionListUsesPreviewFromFirstUserMessageWithoutSettingName() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-name-test");
        SessionFactory sessionFactory = new SessionFactory(sessionConfig(tempDir));
        String sessionId;
        try (Session session = sessionFactory.openSession()) {
            sessionId = session.sessionId();
            TurnContext turnContext = new TurnContext(TurnId.create(), sessionId, 1, tempDir);
            session.recordUserMessage(session.contextBuilder().userMessage("Plan a clean refactor"), turnContext);
            session.waitForIdle();
        }

        try (CommandManager commandManager = new CommandManager(sessionFactory, null, null)) {
            UiSessionSummary summary = commandManager.listSessions()
                    .stream()
                    .filter(item -> sessionId.equals(item.sessionId()))
                    .findFirst()
                    .orElseThrow();

            assertNull(summary.name());
            assertEquals("Plan a clean refactor", summary.preview());
        }
    }

    public void testSetSessionNamePersistsExplicitName() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-set-name-test");
        SessionFactory sessionFactory = new SessionFactory(sessionConfig(tempDir));
        String sessionId;
        try (CommandManager commandManager = new CommandManager(sessionFactory, null, null)) {
            sessionId = commandManager.sessionId();
            UiCommandAck result = commandManager.handle(UiCommand.setSessionName("  Custom title  "));
            assertTrue(result.accepted());

            UiSessionSummary summary = commandManager.listSessions()
                    .stream()
                    .filter(item -> sessionId.equals(item.sessionId()))
                    .findFirst()
                    .orElseThrow();

            assertEquals("Custom title", summary.name());
            assertNull(summary.preview());
        }
    }

    public void testResumeCommandReplaysRestoredTimelineWithoutReemitting() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-test");
        SessionFactory sessionFactory = new SessionFactory(sessionConfig(tempDir));
        Session original = sessionFactory.openSession();
        String sessionId = original.sessionId();
        TurnContext turnContext = new TurnContext(TurnId.create(), sessionId, 1, tempDir);
        original.events().emit(UiEvents.turnStarted(turnContext));
        original.events().emit(UiEvents.turnCompleted(turnContext));
        original.waitForIdle();
        original.close();

        List<UiEvent> received = new CopyOnWriteArrayList<>();
        CommandManager commandManager = new CommandManager(sessionFactory, received::add, null);
        try {
            UiCommand command = UiCommand.resumeSession(sessionId);
            UiCommandAck result = commandManager.handle(command);

            assertTrue(result.accepted());
            assertEquals(command.getCommandId(), result.commandId());
            assertEquals(sessionId, result.sessionId());
            assertNotNull(result.history());
            assertEquals(1, result.history().turns().size());
            assertEquals("COMPLETED", result.history().turns().getFirst().status());
            assertTrue(received.isEmpty());
        } finally {
            commandManager.close();
        }
    }

    private SessionConfig sessionConfig(Path cwd) {
        return new SessionConfig(
                new LlmClient(new ProviderRegistry().register(new NoopProvider())),
                "You are a test agent.",
                cwd.toAbsolutePath().normalize(),
                LlmModel.builder()
                        .id("fake-model")
                        .api("fake")
                        .provider("fake")
                        .input(List.of("text"))
                        .contextWindowTokens(100_000L)
                        .build(),
                RequestAuth.ok("test", Map.of()),
                null,
                new TranscriptStore(cwd.resolve("transcripts")),
                List.of(),
                PromptResources.empty(),
                List.of()
        );
    }

    private static final class NoopProvider implements Provider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ProviderSession openSession(LlmModel model, RequestAuth auth) {
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
