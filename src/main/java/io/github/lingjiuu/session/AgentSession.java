package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.AgentLoop;
import io.github.lingjiuu.agent.AgentRuntime;
import io.github.lingjiuu.agent.AgentRuntimeState;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.model.ConversationHistory;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentSession {

    private final AuthStorage authStorage;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentConfig config;
    private final ConversationHistory history;
    private final AgentLoop agentLoop;
    private final String sessionId;
    private final long createdAt;
    private final List<AgentSessionEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile long updatedAt;
    private volatile AgentSessionStatus status;

    public AgentSession(
            AuthStorage authStorage,
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            AgentConfig config,
            ConversationHistory history,
            AgentLoop agentLoop
    ) {
        this.authStorage = authStorage;
        this.modelRegistry = modelRegistry;
        this.toolRegistry = toolRegistry;
        this.sessionId = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
        this.status = AgentSessionStatus.IDLE;
        this.config = config;
        this.history = history;
        this.agentLoop = agentLoop;
    }

    public synchronized void prompt(String content) {
        ensureNotRunning();
        appendUserMessage(content);
        runLoop();
    }

    public synchronized void continueSession() {
        ensureNotRunning();
        if (!canContinue()) {
            throw new IllegalStateException("Current session cannot continue without a new user or tool result message.");
        }
        runLoop();
    }

    public synchronized void reset() {
        ensureNotRunning();
        history.clear();
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
        toolRegistry.register(definition);
        config.getTools().clear();
        config.getTools().addAll(toolRegistry.toAgentTools());
        updatedAt = System.currentTimeMillis();
    }

    public synchronized boolean canContinue() {
        if (history.isEmpty()) {
            return false;
        }
        Message lastMessage = history.lastMessage();
        return lastMessage == null || lastMessage.role() != Message.Role.ASSISTANT;
    }

    public synchronized List<Message> messages() {
        return List.copyOf(new ArrayList<>(history.snapshot()));
    }

    public Runnable subscribe(AgentSessionEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public AgentConfig config() {
        return config;
    }

    public AgentSessionSnapshot snapshot() {
        return AgentSessionSnapshot.builder()
                .config(config)
                .messages(history.snapshot())
                .build();
    }

    public ModelRegistry modelRegistry() {
        return modelRegistry;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public AuthStorage authStorage() {
        return authStorage;
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
        history.append(userMessage);
        updatedAt = System.currentTimeMillis();
        emit(AgentSessionEvent.builder()
                .type(AgentSessionEvent.Type.USER_MESSAGE)
                .sessionId(sessionId)
                .userMessage(userMessage)
                .text(content)
                .build());
    }

    private void runLoop() {
        status = AgentSessionStatus.RUNNING;
        updatedAt = System.currentTimeMillis();
        AgentRuntime runtime = new AgentRuntime(new AgentRuntimeState(history.snapshot()), agentLoop);
        try {
            runtime.run(this::forwardAgentEvent);
        } catch (RuntimeException e) {
            emit(AgentSessionEvent.builder()
                    .type(AgentSessionEvent.Type.ERROR)
                    .sessionId(sessionId)
                    .errorMessage(e.getMessage())
                    .build());
            throw e;
        } finally {
            synchronizeHistory(runtime.state());
            status = AgentSessionStatus.IDLE;
            updatedAt = System.currentTimeMillis();
        }
    }

    private void ensureNotRunning() {
        if (status == AgentSessionStatus.RUNNING) {
            throw new IllegalStateException("Agent session is already running.");
        }
    }

    private void forwardAgentEvent(AgentEvent event) {
        AgentSessionEvent mappedEvent = AgentSessionEventMapper.map(sessionId, event);
        if (mappedEvent != null) {
            emit(mappedEvent);
        }
    }

    private void emit(AgentSessionEvent event) {
        for (AgentSessionEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    private void synchronizeHistory(AgentRuntimeState runtimeState) {
        history.clear();
        history.appendAll(runtimeState.snapshot());
    }
}
