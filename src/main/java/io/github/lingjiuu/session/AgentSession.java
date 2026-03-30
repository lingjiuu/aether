package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.AgentLoop;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.message.AgentMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.AgentState;
import io.github.lingjiuu.model.AgentTool;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentSession {

    private final AuthStorage authStorage;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentState state;
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
            AgentState state,
            AgentLoop agentLoop
    ) {
        this.authStorage = authStorage;
        this.modelRegistry = modelRegistry;
        this.toolRegistry = toolRegistry;
        this.sessionId = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
        this.status = AgentSessionStatus.IDLE;
        this.state = state;
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
        state.getMessages().clear();
        updatedAt = System.currentTimeMillis();
        emit(AgentSessionEvent.builder()
                .type(AgentSessionEvent.Type.SESSION_RESET)
                .sessionId(sessionId)
                .build());
    }

    public synchronized void registerTool(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        state.getTools().add(tool);
        toolRegistry.register(tool);
        updatedAt = System.currentTimeMillis();
    }

    public synchronized boolean canContinue() {
        if (state.getMessages().isEmpty()) {
            return false;
        }
        AgentMessage lastMessage = state.getMessages().get(state.getMessages().size() - 1);
        return !(lastMessage instanceof Message message && message.role() == Message.Role.ASSISTANT);
    }

    public synchronized List<AgentMessage> messages() {
        return List.copyOf(new ArrayList<>(state.getMessages()));
    }

    public Runnable subscribe(AgentSessionEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public AgentState state() {
        return state;
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
        state.getMessages().add(userMessage);
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
        try {
            agentLoop.run(this::forwardAgentEvent);
        } catch (RuntimeException e) {
            emit(AgentSessionEvent.builder()
                    .type(AgentSessionEvent.Type.ERROR)
                    .sessionId(sessionId)
                    .errorMessage(e.getMessage())
                    .build());
            throw e;
        } finally {
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
        if (event == null || event.getType() == null) {
            return;
        }

        AgentSessionEvent.Type type = switch (event.getType()) {
            case RUN_START -> AgentSessionEvent.Type.RUN_START;
            case TURN_START -> AgentSessionEvent.Type.TURN_START;
            case ASSISTANT_TEXT_DELTA -> AgentSessionEvent.Type.ASSISTANT_TEXT_DELTA;
            case REASONING_DELTA -> AgentSessionEvent.Type.REASONING_DELTA;
            case ASSISTANT_MESSAGE -> AgentSessionEvent.Type.ASSISTANT_MESSAGE;
            case TOOL_CALL -> AgentSessionEvent.Type.TOOL_CALL;
            case TOOL_RESULT -> AgentSessionEvent.Type.TOOL_RESULT;
            case FINAL_ANSWER -> AgentSessionEvent.Type.FINAL_ANSWER;
            case RUN_END -> AgentSessionEvent.Type.RUN_END;
        };

        emit(AgentSessionEvent.builder()
                .type(type)
                .sessionId(sessionId)
                .turn(event.getTurn())
                .delta(event.getDelta())
                .text(event.getText())
                .assistantMessage(event.getAssistantMessage())
                .toolCall(event.getToolCall())
                .toolResult(event.getToolResult())
                .build());
    }

    private void emit(AgentSessionEvent event) {
        for (AgentSessionEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
