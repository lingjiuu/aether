package io.github.lingjiuu.ui.command;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnId;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiModelCatalog;
import io.github.lingjiuu.protocol.UiSessionSummary;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    public void testSetModelUpdatesActiveSelection() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-set-model-test");
        try (CommandManager commandManager = new CommandManager(new SessionFactory(sessionConfig(tempDir)), null, null)) {
            UiCommandAck result = commandManager.handle(UiCommand.setModel(null, "fake-model", "HIGH"));

            assertTrue(result.accepted());
            assertTrue(result.message().contains("Set model to fake/fake-model high"));
            assertEquals(
                    io.github.lingjiuu.llm.ReasoningOptions.ReasoningEffort.HIGH,
                    commandManager.currentSession().activeModelSelection().reasoning().getReasoningEffort()
            );

            commandManager.currentSession().waitForIdle();
            assertTrue(commandManager.currentSession()
                    .eventsAfter(0)
                    .stream()
                    .anyMatch(event -> event.getType() == UiEventType.MODEL_CHANGED));
        }
    }

    public void testModelCatalogMarksCurrentModel() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-model-catalog-test");
        try (CommandManager commandManager = new CommandManager(new SessionFactory(sessionConfig(tempDir)), null, null)) {
            UiModelCatalog catalog = commandManager.modelCatalog();

            assertEquals("fake", catalog.current().providerId());
            assertEquals("fake-model", catalog.current().modelId());
            assertEquals(1, catalog.models().size());
            assertTrue(catalog.models().getFirst().current());
            assertTrue(catalog.reasoningEfforts().contains("HIGH"));
        }
    }

    public void testSetModelAffectsNextTurnRequest() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-set-model-turn-test");
        CapturingProvider provider = new CapturingProvider();
        try (CommandManager commandManager = new CommandManager(new SessionFactory(sessionConfig(tempDir, provider)), null, null)) {
            commandManager.handle(UiCommand.setModel(null, "fake-model", "HIGH"));
            commandManager.handle(UiCommand.submitUserInput("hello"));
            commandManager.currentSession().waitForIdle();

            assertNotNull(provider.lastRequest);
            assertEquals("fake-model", provider.lastRequest.getModel().getId());
            assertEquals(
                    io.github.lingjiuu.llm.ReasoningOptions.ReasoningEffort.HIGH,
                    provider.lastRequest.getCallOptions().getReasoning().getReasoningEffort()
            );
        }
    }

    public void testPromptContextIsSentAsMessages() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-prompt-context-test");
        CapturingProvider provider = new CapturingProvider();
        try (CommandManager commandManager = new CommandManager(new SessionFactory(sessionConfig(
                tempDir,
                provider,
                "Prefer small diffs.",
                "Project rules.",
                List.of(tempDir.resolve("AGENTS.md"))
        )), null, null)) {
            commandManager.handle(UiCommand.submitUserInput("hello"));
            commandManager.currentSession().waitForIdle();

            assertNotNull(provider.lastRequest);
            assertEquals("You are a test agent.", provider.lastRequest.getBaseInstructions());
            assertFalse(provider.lastRequest.getBaseInstructions().contains("Project rules."));
            assertTrue(provider.lastRequest.getMessages().stream()
                    .map(MessageContents::text)
                    .anyMatch(text -> text.startsWith("<additional_instructions>")
                            && text.contains("Prefer small diffs.")));
            assertTrue(provider.lastRequest.getMessages().stream()
                    .map(MessageContents::text)
                    .anyMatch(text -> text.startsWith("# AGENTS.md instructions for ")
                            && text.contains("<INSTRUCTIONS>")
                            && text.contains("Project rules.")));
        }
    }

    public void testSetModelIsDisabledWhileTurnRuns() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-command-set-model-running-test");
        BlockingProvider provider = new BlockingProvider();
        CommandManager commandManager = new CommandManager(new SessionFactory(sessionConfig(tempDir, provider)), null, null);
        try {
            commandManager.handle(UiCommand.submitUserInput("hello"));
            assertTrue(provider.started.await(2, TimeUnit.SECONDS));

            UiCommandAck result = commandManager.handle(UiCommand.setModel(null, "fake-model", "HIGH"));

            assertFalse(result.accepted());
            assertTrue(result.message().contains("disabled while a task is in progress"));
        } finally {
            provider.release.countDown();
            commandManager.close();
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
        return sessionConfig(cwd, new NoopProvider());
    }

    private SessionConfig sessionConfig(Path cwd, Provider provider) {
        return sessionConfig(cwd, provider, "", "", List.of());
    }

    private SessionConfig sessionConfig(
            Path cwd,
            Provider provider,
            String developerInstructions,
            String userInstructions,
            List<Path> instructionSources
    ) {
        return new SessionConfig(
                new LlmClient(new ProviderRegistry().register(provider)),
                "You are a test agent.",
                developerInstructions,
                userInstructions,
                instructionSources,
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
                List.of()
        );
    }

    private static class CapturingProvider extends NoopProvider {
        private volatile LlmRequest lastRequest;

        @Override
        public ProviderSession openSession(LlmModel model, RequestAuth auth) {
            ProviderSession delegate = super.openSession(model, auth);
            return (request, cancellationToken) -> {
                lastRequest = request;
                return delegate.stream(request, cancellationToken);
            };
        }
    }

    private static class BlockingProvider extends NoopProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ProviderSession openSession(LlmModel model, RequestAuth auth) {
            return (request, cancellationToken) -> new AssistantStream() {
                @Override
                public io.github.lingjiuu.message.AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
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

    private static class NoopProvider implements Provider {
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
