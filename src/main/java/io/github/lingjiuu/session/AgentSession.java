package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.runtime.AgentRunOptions;
import io.github.lingjiuu.agent.runtime.AgentRuntime;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.ToolCancellationSource;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.transcript.TranscriptRecorder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentSession {

    private final AgentSessionServices services;
    private final AgentLoop agentLoop;
    private final TranscriptRecorder transcriptRecorder;
    private final List<Message> messages = new ArrayList<>();
    private final String sessionId;
    private final long createdAt;
    private final List<AgentSessionEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile long updatedAt;
    private volatile AgentSessionStatus status;
    private volatile ActiveRun activeRun;

    public AgentSession(
            AgentSessionServices services,
            AgentLoop agentLoop
    ) {
        this(services, agentLoop, UUID.randomUUID().toString(), List.of(), null);
    }

    AgentSession(
            AgentSessionServices services,
            AgentLoop agentLoop,
            String sessionId,
            List<Message> initialMessages,
            String lastTranscriptRecordId
    ) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        if (agentLoop == null) {
            throw new IllegalArgumentException("agentLoop must not be null");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.services = services;
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
        this.status = AgentSessionStatus.IDLE;
        this.agentLoop = agentLoop;
        this.transcriptRecorder = services.getTranscriptStore() == null
                ? null
                : new TranscriptRecorder(
                services.getTranscriptStore(),
                sessionId,
                lastTranscriptRecordId
        );
        if (initialMessages != null) {
            messages.addAll(initialMessages);
        }
    }

    public void prompt(String content) {
        ActiveRun run;
        synchronized (this) {
            ensureNotRunning();
            appendUserMessage(content);
            run = startRun();
        }
        runLoop(run);
    }

    public void continueSession() {
        ActiveRun run;
        synchronized (this) {
            ensureNotRunning();
            if (!canContinue()) {
                throw new IllegalStateException("Current session cannot continue without a new user or tool result message.");
            }
            run = startRun();
        }
        runLoop(run);
    }

    public synchronized void reset() {
        ensureNotRunning();
        clearMessages();
        if (transcriptRecorder != null) {
            transcriptRecorder.resetParent(null);
        }
        updatedAt = System.currentTimeMillis();
        emit(AgentSessionEvent.builder()
                .type(AgentSessionEvent.Type.SESSION_RESET)
                .sessionId(sessionId)
                .build());
    }

    public synchronized void registerTool(ToolDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("tool definition must not be null");
        }
        services.getToolRegistry().register(definition);
        updatedAt = System.currentTimeMillis();
    }

    public synchronized void setActiveToolsByName(List<String> toolNames) {
        ensureNotRunning();
        services.setActiveToolNames(toolNames);
        updatedAt = System.currentTimeMillis();
    }

    public synchronized List<String> activeToolNames() {
        return services.activeToolNames();
    }

    public synchronized boolean canContinue() {
        if (messages.isEmpty()) {
            return false;
        }
        Message lastMessage = lastMessage();
        return lastMessage == null || lastMessage.role() != Message.Role.ASSISTANT;
    }

    public synchronized List<Message> messages() {
        return snapshotMessages();
    }

    public void abort() {
        ActiveRun run = activeRun;
        if (run != null) {
            run.cancellationSource().cancel();
        }
    }

    public synchronized void waitForIdle() {
        while (status == AgentSessionStatus.RUNNING) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for session to become idle.", e);
            }
        }
    }

    public synchronized boolean waitForIdle(Duration timeout) {
        if (timeout == null) {
            waitForIdle();
            return true;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (status == AgentSessionStatus.RUNNING) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                long millis = Math.max(1L, remainingNanos / 1_000_000L);
                wait(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for session to become idle.", e);
            }
        }
        return true;
    }

    public Runnable subscribe(AgentSessionEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public AgentSessionConfig config() {
        return services.getConfig();
    }

    public AgentSessionSnapshot snapshot() {
        return AgentSessionSnapshot.builder()
                .config(config())
                .messages(snapshotMessages())
                .build();
    }

    public ModelRegistry modelRegistry() {
        return services.getModelRegistry();
    }

    public io.github.lingjiuu.tool.ToolRegistry toolRegistry() {
        return services.getToolRegistry();
    }

    public AuthStorage authStorage() {
        return config().getAuthStorage();
    }

    public String sessionId() {
        return sessionId;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public AgentSessionStatus status() {
        return status;
    }

    private void appendUserMessage(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("prompt content must not be blank");
        }

        UserMessage userMessage = UserMessage.builder()
                .contents(List.of(
                        TextContent.builder()
                                .text(content)
                                .build()
                ))
                .build();
        appendMessage(userMessage);
        recordTranscript(userMessage, 0);
        updatedAt = System.currentTimeMillis();
        emit(AgentSessionEvent.builder()
                .type(AgentSessionEvent.Type.USER_MESSAGE)
                .sessionId(sessionId)
                .userMessage(userMessage)
                .text(content)
                .build());
    }

    private ActiveRun startRun() {
        status = AgentSessionStatus.RUNNING;
        updatedAt = System.currentTimeMillis();
        AgentRuntime runtime = new AgentRuntime(new AgentRuntimeState(snapshotMessages()), agentLoop);
        ActiveRun run = new ActiveRun(runtime, new ToolCancellationSource());
        activeRun = run;
        return run;
    }

    private void runLoop(ActiveRun run) {
        try {
            run.runtime().run(
                    this::forwardAgentEvent,
                    AgentRunOptions.withCancellationToken(run.cancellationSource().token())
            );
        } catch (RuntimeException e) {
            emit(AgentSessionEvent.builder()
                    .type(AgentSessionEvent.Type.ERROR)
                    .sessionId(sessionId)
                    .errorMessage(e.getMessage())
                    .build());
            throw e;
        } finally {
            synchronized (this) {
                synchronizeHistory(run.runtime().state());
                if (activeRun == run) {
                    activeRun = null;
                }
                status = AgentSessionStatus.IDLE;
                updatedAt = System.currentTimeMillis();
                notifyAll();
            }
        }
    }

    private void ensureNotRunning() {
        if (status == AgentSessionStatus.RUNNING) {
            throw new IllegalStateException("Agent session is already running.");
        }
    }

    private void forwardAgentEvent(AgentEvent event) {
        recordTranscript(event);
        AgentSessionEvent mappedEvent = mapAgentEvent(event);
        if (mappedEvent != null) {
            emit(mappedEvent);
        }
    }

    private void recordTranscript(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }
        switch (event.getType()) {
            case ASSISTANT_MESSAGE -> recordTranscript(event.getAssistantMessage(), event.getTurn());
            case TOOL_RESULT -> recordTranscript(event.getToolResult(), event.getTurn());
            case SYSTEM_MESSAGE -> recordTranscript(event.getSystemMessage(), event.getTurn());
            case ATTACHMENT_MESSAGE -> recordTranscript(event.getAttachmentMessage(), event.getTurn());
            default -> {
            }
        }
    }

    private void recordTranscript(Message message, int turn) {
        if (transcriptRecorder != null && message != null) {
            transcriptRecorder.record(message, turn);
        }
    }

    private void emit(AgentSessionEvent event) {
        for (AgentSessionEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    private void synchronizeHistory(AgentRuntimeState runtimeState) {
        replaceMessages(runtimeState.snapshot());
    }

    private AgentSessionEvent mapAgentEvent(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return null;
        }

        AgentSessionEvent.Type type = switch (event.getType()) {
            case RUN_START -> AgentSessionEvent.Type.RUN_START;
            case TURN_START -> AgentSessionEvent.Type.TURN_START;
            case ASSISTANT_TEXT_DELTA -> AgentSessionEvent.Type.ASSISTANT_TEXT_DELTA;
            case REASONING_DELTA -> AgentSessionEvent.Type.REASONING_DELTA;
            case ASSISTANT_MESSAGE -> AgentSessionEvent.Type.ASSISTANT_MESSAGE;
            case TOOL_CALL -> AgentSessionEvent.Type.TOOL_CALL;
            case TOOL_EXECUTION_START -> AgentSessionEvent.Type.TOOL_EXECUTION_START;
            case TOOL_EXECUTION_UPDATE -> AgentSessionEvent.Type.TOOL_EXECUTION_UPDATE;
            case TOOL_EXECUTION_END -> AgentSessionEvent.Type.TOOL_EXECUTION_END;
            case TOOL_RESULT -> AgentSessionEvent.Type.TOOL_RESULT;
            case SYSTEM_MESSAGE -> AgentSessionEvent.Type.SYSTEM_MESSAGE;
            case ATTACHMENT_MESSAGE -> AgentSessionEvent.Type.ATTACHMENT_MESSAGE;
            case FINAL_ANSWER -> AgentSessionEvent.Type.FINAL_ANSWER;
            case RUN_END -> AgentSessionEvent.Type.RUN_END;
        };

        return AgentSessionEvent.builder()
                .type(type)
                .sessionId(sessionId)
                .turn(event.getTurn())
                .delta(event.getDelta())
                .text(event.getText())
                .assistantMessage(event.getAssistantMessage())
                .toolCall(event.getToolCall())
                .toolResult(event.getToolResult())
                .systemMessage(event.getSystemMessage())
                .attachmentMessage(event.getAttachmentMessage())
                .partialToolResult(event.getPartialToolResult())
                .build();
    }

    private void appendMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(message);
    }

    private void replaceMessages(List<Message> newMessages) {
        clearMessages();
        messages.addAll(newMessages);
    }

    private void clearMessages() {
        messages.clear();
    }

    private List<Message> snapshotMessages() {
        return List.copyOf(messages);
    }

    private Message lastMessage() {
        return messages.isEmpty() ? null : messages.getLast();
    }

    private record ActiveRun(AgentRuntime runtime, ToolCancellationSource cancellationSource) {
    }
}
