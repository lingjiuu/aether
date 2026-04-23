package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.runtime.AgentRuntime;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentSession {

    private final AgentSessionServices services;
    private final AgentLoop agentLoop;
    private final List<Message> messages = new ArrayList<>();
    private final String sessionId;
    private final long createdAt;
    private final List<AgentSessionEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile long updatedAt;
    private volatile AgentSessionStatus status;

    public AgentSession(
            AgentSessionServices services,
            AgentLoop agentLoop
    ) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        if (agentLoop == null) {
            throw new IllegalArgumentException("agentLoop must not be null");
        }
        this.services = services;
        this.sessionId = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
        this.status = AgentSessionStatus.IDLE;
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
        clearMessages();
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
        AgentRuntime runtime = new AgentRuntime(new AgentRuntimeState(snapshotMessages()), agentLoop);
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
        AgentSessionEvent mappedEvent = mapAgentEvent(event);
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
}
