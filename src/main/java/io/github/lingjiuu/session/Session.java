package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.task.CompactTask;
import io.github.lingjiuu.agent.task.RegularTask;
import io.github.lingjiuu.agent.task.RunningTask;
import io.github.lingjiuu.agent.task.SessionTask;
import io.github.lingjiuu.agent.task.TaskContext;
import io.github.lingjiuu.agent.task.TaskRunner;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnId;
import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.context.ContextManager;
import io.github.lingjiuu.context.EnvironmentContext;
import io.github.lingjiuu.event.EventManager;
import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.event.EventSubscription;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.event.UiEventType;
import io.github.lingjiuu.input.InputMaterializer;
import io.github.lingjiuu.input.MaterializedInput;
import io.github.lingjiuu.input.TurnInput;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClientSession;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.prompt.Prompt;
import io.github.lingjiuu.prompt.PromptBuilder;
import io.github.lingjiuu.recording.MessageRecorder;
import io.github.lingjiuu.tool.ToolCancellationToken;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolCancellationSource;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.permission.ApprovalHandler;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.tool.permission.DenyAllApprovalHandler;
import io.github.lingjiuu.tool.permission.PermissionManager;
import io.github.lingjiuu.transcript.TranscriptRecorder;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public class Session {

    private final SessionConfig config;
    private final SessionState state;
    private final ContextManager contextManager;
    private final ContextBuilder contextBuilder = new ContextBuilder();
    private final MessageRecorder recorder;
    private final EventManager eventManager = new EventManager();
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ToolRegistry toolRegistry;
    private final ToolRunner toolRunner;
    private final PermissionManager permissionManager = new PermissionManager();
    private final TaskRunner taskRunner = new TaskRunner();
    private final InputMaterializer inputMaterializer;
    private volatile List<String> activeToolNames;
    private volatile ApprovalHandler approvalHandler = new DenyAllApprovalHandler();

    Session(
            SessionConfig config,
            ToolRegistry toolRegistry,
            String sessionId,
            List<Message> initialMessages,
            String lastTranscriptRecordId,
            SessionMetaItem sessionMeta,
            boolean recordSessionMeta
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        this.config = config;
        this.state = new SessionState(sessionId);
        this.contextManager = new ContextManager(null, initialMessages);
        this.toolRegistry = toolRegistry;
        this.activeToolNames = config.activeToolNames();
        this.inputMaterializer = new InputMaterializer(config.cwd());
        TranscriptRecorder transcriptRecorder = config.transcriptStore() == null
                ? null
                : new TranscriptRecorder(config.transcriptStore(), sessionId, lastTranscriptRecordId);
        this.recorder = new MessageRecorder(
                contextManager,
                transcriptRecorder
        );
        if (recordSessionMeta && transcriptRecorder != null) {
            transcriptRecorder.recordSessionMeta(sessionMeta);
        }
        this.toolRunner = new ToolRunner(toolRegistry);
    }

    public void prompt(String content) {
        submit(TurnInput.ofText(content));
    }

    public void submit(TurnInput input) {
        submitAsync(input);
        waitForIdle();
    }

    public void submitAsync(TurnInput input) {
        MaterializedInput materializedInput = inputMaterializer.materialize(input);
        synchronized (this) {
            ensureIdle();
            state.touch();
        }
        runRegularTask(materializedInput);
    }

    public void continueSession() {
        synchronized (this) {
            ensureIdle();
            if (!canContinue()) {
                throw new IllegalStateException("Current session cannot continue without a new user or tool result message.");
            }
        }
        runRegularTask(null);
        waitForIdle();
    }

    public void compact() {
        compactAsync();
        waitForIdle();
    }

    public void compactAsync() {
        synchronized (this) {
            ensureIdle();
        }
        runSessionTask(new CompactTask());
    }

    public synchronized void reset() {
        ensureIdle();
        recorder.clear();
        invalidateReferenceEnvironmentContext();
        state.touch();
        eventManager.emit(UiEvent.builder()
                .type(UiEventType.SESSION_RESET)
                .sessionId(sessionId())
                .build());
    }

    public synchronized void registerTool(ToolDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("tool definition must not be null");
        }
        toolRegistry.register(definition);
        state.touch();
    }

    public synchronized void setActiveToolsByName(List<String> toolNames) {
        ensureIdle();
        activeToolNames = toolNames == null ? null : List.copyOf(toolNames);
        state.touch();
    }

    public synchronized void setApprovalHandler(ApprovalHandler approvalHandler) {
        this.approvalHandler = approvalHandler == null ? new DenyAllApprovalHandler() : approvalHandler;
        state.touch();
    }

    public List<String> activeToolNames() {
        return toolRegistry.activeDefinitions(activeToolNames)
                .stream()
                .map(ToolDefinition::name)
                .toList();
    }

    public List<ToolDefinition> activeTools() {
        return toolRegistry.activeDefinitions(activeToolNames);
    }

    public synchronized boolean canContinue() {
        Message lastMessage = contextManager.lastMessage();
        return lastMessage != null && lastMessage.role() != Message.Role.ASSISTANT;
    }

    public List<Message> messages() {
        return contextManager.snapshot();
    }

    public void abort() {
        cancelRunningTask();
    }

    public boolean cancelRunningTask() {
        return taskRunner.cancelRunningTask();
    }

    public synchronized void waitForIdle() {
        while (state.status() == SessionStatus.RUNNING) {
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
        while (state.status() == SessionStatus.RUNNING) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                wait(Math.max(1L, remainingNanos / 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for session to become idle.", e);
            }
        }
        return true;
    }

    public EventSubscription subscribe(EventSink sink) {
        return eventManager.subscribe(sink);
    }

    public SessionConfig config() {
        return config;
    }

    public ContextManager contextManager() {
        return contextManager;
    }

    public ContextBuilder contextBuilder() {
        return contextBuilder;
    }

    public MessageRecorder recorder() {
        return recorder;
    }

    public PromptBuilder promptBuilder() {
        return promptBuilder;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public ToolRunner toolRunner() {
        return toolRunner;
    }

    public PermissionManager permissionManager() {
        return permissionManager;
    }

    public void emit(UiEvent event) {
        eventManager.emit(event);
    }

    public void recordUserMessageAndEmit(UserMessage userMessage, TurnContext turnContext) {
        if (userMessage == null) {
            return;
        }
        recorder.record(userMessage, turnContext.turn());
        emit(UiEvent.builder()
                .type(UiEventType.USER_MESSAGE)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .userMessage(userMessage)
                .text(MessageContents.text(userMessage))
                .build());
    }

    public void recordContextMessageAndEmit(ContextMessage contextMessage, TurnContext turnContext) {
        if (contextMessage == null) {
            return;
        }
        recorder.record(contextMessage, turnContext.turn());
        emit(UiEvent.builder()
                .type(UiEventType.CONTEXT_MESSAGE)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .contextMessage(contextMessage)
                .text(MessageContents.text(contextMessage))
                .build());
    }

    public void recordAssistantAndEmit(AssistantMessage assistantMessage, TurnContext turnContext) {
        if (assistantMessage == null) {
            return;
        }
        recorder.record(assistantMessage, turnContext.turn());
        emit(UiEvent.builder()
                .type(UiEventType.ASSISTANT_MESSAGE)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .assistantMessage(assistantMessage)
                .text(MessageContents.text(assistantMessage))
                .build());
    }

    public void recordToolResultAndEmit(
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext
    ) {
        if (toolResult == null) {
            return;
        }
        recorder.record(toolResult, turnContext.turn());
        String resultText = MessageContents.text(toolResult);
        emit(UiEvent.builder()
                .type(UiEventType.TOOL_EXECUTION_FINISHED)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .toolCall(toolCall)
                .toolResult(toolResult)
                .text(resultText)
                .build());
        emit(UiEvent.builder()
                .type(UiEventType.TOOL_RESULT)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .toolResult(toolResult)
                .text(resultText)
                .build());
    }

    public ToolResultMessage toolResultMessage(ToolCallContent toolCall, ToolExecutionResult result) {
        return contextBuilder.toolResultMessage(toolCall, result);
    }

    public void emitFinalAnswer(AssistantMessage assistantMessage, TurnContext turnContext) {
        emit(UiEvent.builder()
                .type(UiEventType.FINAL_ANSWER)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .assistantMessage(assistantMessage)
                .text(MessageContents.text(assistantMessage))
                .build());
    }

    public void emitError(TurnContext turnContext, String message) {
        emit(UiEvent.builder()
                .type(UiEventType.ERROR)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .errorMessage(message)
                .build());
    }

    public ApprovalResponse requestApproval(ApprovalRequest request, TurnContext turnContext) {
        if (request == null) {
            return null;
        }
        emit(UiEvent.builder()
                .type(UiEventType.APPROVAL_REQUESTED)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .approvalRequest(request)
                .build());

        ApprovalResponse response = approvalHandler.requestApproval(request);
        if (response == null || !request.id().equals(response.id())) {
            response = ApprovalResponse.deny(request.id(), "Approval response did not match the request.");
        }

        emit(UiEvent.builder()
                .type(UiEventType.APPROVAL_RESOLVED)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .approvalRequest(request)
                .approvalResponse(response)
                .build());
        return response;
    }

    public void recordEnvironmentContextIfChanged(TurnContext turnContext) {
        EnvironmentContext current = contextBuilder.environmentContext(turnContext.cwd());
        EnvironmentContext previous = state.referenceEnvironmentContext();
        contextBuilder.environmentMessage(previous, current).ifPresent(message -> {
            recordContextMessageAndEmit(message, turnContext);
        });
        state.setReferenceEnvironmentContext(current);
    }

    public void invalidateReferenceEnvironmentContext() {
        state.setReferenceEnvironmentContext(null);
    }

    public AssistantMessage sampleModel(
            LlmClientSession modelSession,
            Prompt prompt,
            TurnContext turnContext,
            ToolCancellationToken cancellationToken
    ) {
        ToolCancellationToken token = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            return abortedMessage();
        }

        AssistantMessage assistantMessage;
        try (AssistantStream stream = modelSession.stream(prompt.toLlmRequest(config.requestAuth()), token)) {
            AutoCloseable cancelRegistration = token.onCancel(() -> closeQuietly(stream));
            try {
                assistantMessage = stream.consume(event -> {
                    if (!token.isCancellationRequested()) {
                        emit(mapStreamEvent(event, turnContext));
                    }
                });
            } finally {
                closeQuietly(cancelRegistration);
            }
        } catch (IOException e) {
            if (token.isCancellationRequested()) {
                return abortedMessage();
            }
            throw new RuntimeException("Failed to close assistant stream.", e);
        } catch (RuntimeException e) {
            if (token.isCancellationRequested()) {
                return abortedMessage();
            }
            throw e;
        }

        if (token.isCancellationRequested()) {
            return abortedMessage();
        }
        return assistantMessage;
    }

    public String sessionId() {
        return state.sessionId();
    }

    public long createdAt() {
        return state.createdAt();
    }

    public long updatedAt() {
        return state.updatedAt();
    }

    public SessionStatus status() {
        return state.status();
    }

    private void runRegularTask(MaterializedInput materializedInput) {
        runSessionTask(new RegularTask(), materializedInput);
    }

    private void runSessionTask(SessionTask task) {
        runSessionTask(task, null);
    }

    private void runSessionTask(SessionTask task, MaterializedInput materializedInput) {
        ToolCancellationSource cancellationSource = new ToolCancellationSource();
        int turn;
        synchronized (this) {
            ensureIdle();
            state.markRunning();
            turn = state.nextTurn();
        }
        TurnContext turnContext = new TurnContext(TurnId.create(), sessionId(), turn, config.cwd());

        eventManager.emit(UiEvent.builder()
                .type(UiEventType.RUN_STARTED)
                .sessionId(sessionId())
                .turn(turn)
                .build());
        try {
            RunningTask runningTask = taskRunner.start(
                    cancellationSource,
                    "aether-" + task.kind().name().toLowerCase() + "-turn-" + turn,
                    () -> runTaskBody(task, turnContext, materializedInput, cancellationSource)
            );
            if (runningTask == null) {
                throw new IllegalStateException("Failed to start session task.");
            }
        } catch (RuntimeException e) {
            eventManager.emit(UiEvent.builder()
                    .type(UiEventType.ERROR)
                    .sessionId(sessionId())
                    .turn(turn)
                    .errorMessage(e.getMessage())
                    .build());
            eventManager.emit(UiEvent.builder()
                    .type(UiEventType.RUN_FINISHED)
                    .sessionId(sessionId())
                    .turn(turn)
                    .build());
            synchronized (this) {
                state.markIdle();
                notifyAll();
            }
            throw e;
        }
    }

    private void runTaskBody(
            SessionTask task,
            TurnContext turnContext,
            MaterializedInput materializedInput,
            ToolCancellationSource cancellationSource
    ) {
        try (LlmClientSession modelSession = config.llmClient().openSession(config.model(), config.requestAuth())) {
            TaskContext context = taskContext(modelSession, cancellationSource, turnContext, materializedInput);
            task.run(context);
        } catch (RuntimeException e) {
            if (!cancellationSource.token().isCancellationRequested()) {
                eventManager.emit(UiEvent.builder()
                        .type(UiEventType.ERROR)
                        .sessionId(sessionId())
                        .turn(turnContext.turn())
                        .errorMessage(e.getMessage())
                        .build());
            }
        } finally {
            boolean cancelled = cancellationSource.token().isCancellationRequested();
            if (cancelled) {
                try {
                    recordInterruptedTurn(turnContext);
                } catch (RuntimeException e) {
                    eventManager.emit(UiEvent.builder()
                            .type(UiEventType.ERROR)
                            .sessionId(sessionId())
                            .turn(turnContext.turn())
                            .errorMessage("Failed to record interrupted turn: " + e.getMessage())
                            .build());
                }
            }
            eventManager.emit(UiEvent.builder()
                    .type(cancelled ? UiEventType.TURN_ABORTED : UiEventType.RUN_FINISHED)
                    .sessionId(sessionId())
                    .turn(turnContext.turn())
                    .build());
            if (cancelled) {
                eventManager.emit(UiEvent.builder()
                        .type(UiEventType.RUN_FINISHED)
                        .sessionId(sessionId())
                        .turn(turnContext.turn())
                        .build());
            }
            synchronized (this) {
                state.markIdle();
                notifyAll();
            }
        }
    }

    private void recordInterruptedTurn(TurnContext turnContext) {
        recordContextMessageAndEmit(contextBuilder.interruptedTurnMessage(), turnContext);
    }

    private TaskContext taskContext(
            LlmClientSession modelSession,
            ToolCancellationSource cancellationSource,
            TurnContext turnContext,
            MaterializedInput materializedInput
    ) {
        return new TaskContext(
                this,
                turnContext,
                materializedInput,
                cancellationSource.token(),
                modelSession
        );
    }

    private UiEvent mapStreamEvent(AssistantStreamEvent event, TurnContext turnContext) {
        if (event == null || event.getType() == null) {
            return null;
        }
        UiEventType type = switch (event.getType()) {
            case TEXT_DELTA -> UiEventType.ASSISTANT_TEXT_DELTA;
            case THINKING_DELTA -> UiEventType.REASONING_DELTA;
            case ERROR -> UiEventType.ERROR;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        return UiEvent.builder()
                .type(type)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .delta(event.getDelta())
                .errorMessage(event.getReason())
                .build();
    }

    private AssistantMessage abortedMessage() {
        return AssistantMessage.builder()
                .stopReason(AssistantMessage.StopReason.ABORTED)
                .contents(List.of(TextContent.builder()
                        .text("")
                        .build()))
                .build();
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void ensureIdle() {
        if (state.status() == SessionStatus.RUNNING) {
            throw new IllegalStateException("Agent session is already running.");
        }
    }
}
