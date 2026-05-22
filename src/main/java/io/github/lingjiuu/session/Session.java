package io.github.lingjiuu.session;

import io.github.lingjiuu.event.UiEvents;
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
import io.github.lingjiuu.context.InitialContextSnapshot;
import io.github.lingjiuu.event.EventManager;
import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.event.EventSubscription;
import io.github.lingjiuu.input.InputMaterializer;
import io.github.lingjiuu.input.MaterializedInput;
import io.github.lingjiuu.input.TurnInput;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClientSession;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.TokenUsage;
import io.github.lingjiuu.llm.TokenUsageInfo;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.prompt.Prompt;
import io.github.lingjiuu.prompt.PromptBuilder;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.recording.MessageRecorder;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.skill.SkillsManager;
import io.github.lingjiuu.skill.SkillsWatcher;
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
import io.github.lingjiuu.transcript.item.TurnContextItem;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public class Session implements AutoCloseable {

    private final SessionConfig config;
    private final SessionState state;
    private final ContextManager contextManager;
    private final ContextBuilder contextBuilder = new ContextBuilder();
    private final MessageRecorder recorder;
    private final EventManager eventManager;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ToolRegistry toolRegistry;
    private final ToolRunner toolRunner;
    private final SkillsManager skillsManager;
    private final SkillsWatcher skillsWatcher;
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
            boolean recordSessionMeta,
            InitialContextSnapshot initialContextBaseline,
            SkillsManager skillsManager,
            List<UiEvent> initialTimelineEvents,
            long initialEventSequence
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
        this.skillsManager = skillsManager == null ? SkillsManager.empty(config.cwd()) : skillsManager;
        this.activeToolNames = config.activeToolNames();
        this.inputMaterializer = new InputMaterializer(config.cwd());
        TranscriptRecorder transcriptRecorder = config.transcriptStore() == null
                ? null
                : new TranscriptRecorder(config.transcriptStore(), sessionId, lastTranscriptRecordId);
        this.recorder = new MessageRecorder(
                contextManager,
                transcriptRecorder
        );
        this.eventManager = new EventManager(transcriptRecorder, initialTimelineEvents, initialEventSequence);
        if (recordSessionMeta && transcriptRecorder != null) {
            transcriptRecorder.recordSessionMeta(sessionMeta);
        }
        this.toolRunner = new ToolRunner(toolRegistry);
        state.setInitialContextBaseline(initialContextBaseline);
        this.skillsWatcher = startSkillsWatcher();
        recomputeTokenUsageFromHistory();
    }

    public void prompt(String content) {
        submit(TurnInput.ofText(content));
    }

    public void submit(TurnInput input) {
        submitAsync(input);
        waitForIdle();
    }

    public void submitAsync(TurnInput input) {
        submitAsync(input, null);
    }

    public void submitAsync(TurnInput input, String commandId) {
        MaterializedInput materializedInput = inputMaterializer.materialize(input);
        synchronized (this) {
            ensureIdle();
            state.touch();
        }
        runRegularTask(materializedInput, commandId);
    }

    public void continueSession() {
        runContinueAsync();
        waitForIdle();
    }

    public void runContinueAsync() {
        runContinueAsync(null);
    }

    public void runContinueAsync(String commandId) {
        synchronized (this) {
            ensureIdle();
            if (!canContinue()) {
                throw new IllegalStateException("Current session cannot continue without a new user or tool result message.");
            }
        }
        runRegularTask(null, commandId);
    }

    public void compact() {
        compactAsync();
        waitForIdle();
    }

    public void compactAsync() {
        compactAsync(null);
    }

    public void compactAsync(String commandId) {
        synchronized (this) {
            ensureIdle();
        }
        runSessionTask(new CompactTask(), null, commandId);
    }

    public synchronized void reset() {
        ensureIdle();
        int originalMessageCount = contextManager.snapshot().size();
        recorder.recordCompaction("session reset", List.of(), 0, originalMessageCount, 0);
        state.clearTokenUsage();
        clearInitialContextBaseline();
        state.touch();
        eventManager.emit(UiEvents.sessionReset(sessionId()));
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

    public List<UiEvent> timelineEvents() {
        return eventManager.timelineEvents();
    }

    public void abort() {
        cancelRunningTask();
    }

    public boolean cancelRunningTask() {
        return taskRunner.cancelRunningTask();
    }

    public void waitForIdle() {
        synchronized (this) {
            while (state.status() == SessionStatus.RUNNING) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for session to become idle.", e);
                }
            }
        }
        eventManager.flush();
    }

    public boolean waitForIdle(Duration timeout) {
        if (timeout == null) {
            waitForIdle();
            return true;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
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
        }
        eventManager.flush();
        return true;
    }

    public EventSubscription subscribe(EventSink sink) {
        return eventManager.subscribe(sink);
    }

    public void replayTimeline(EventSink sink) {
        eventManager.replayTimeline(sink);
    }

    public List<UiEvent> eventsAfter(long sequence) {
        return eventManager.eventsAfter(sequence);
    }

    public EventManager events() {
        return eventManager;
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

    public SkillsManager skillsManager() {
        return skillsManager;
    }

    public List<Skill> availableSkills() {
        return skillsManager.availableSkills();
    }

    public void reloadSkills() {
        emitSkillsChanged(skillsManager.reload());
    }

    public PermissionManager permissionManager() {
        return permissionManager;
    }

    public TokenUsageInfo tokenUsageInfo() {
        return state.tokenUsageInfo();
    }

    public long currentContextTokenUsage() {
        TokenUsageInfo tokenUsageInfo = state.tokenUsageInfo();
        TokenUsage lastUsage = tokenUsageInfo == null ? null : tokenUsageInfo.lastTokenUsage();
        if (lastUsage != null && lastUsage.totalTokens() > 0) {
            long addedSinceLastAssistant = contextManager.estimateTokensAfterLastAssistantMessage();
            if (addedSinceLastAssistant >= 0) {
                return lastUsage.totalTokens() + addedSinceLastAssistant;
            }
        }
        return contextManager.estimateTokensForModel(config.systemPrompt());
    }

    public boolean shouldAutoCompact() {
        if (!contextManager.policy().autoCompactEnabled()) {
            return false;
        }
        Long limit = autoCompactTokenLimit();
        return limit != null
                && limit > 0
                && contextManager.snapshot().size() > 1
                && currentContextTokenUsage() >= limit;
    }

    public boolean isContextWindowExceeded(AssistantMessage assistantMessage) {
        if (assistantMessage == null || assistantMessage.getStopReason() != AssistantMessage.StopReason.ERROR) {
            return false;
        }
        String errorMessage = assistantMessage.getErrorMessage();
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        String normalized = errorMessage.toLowerCase();
        return normalized.contains("context_window_exceeded")
                || normalized.contains("context window")
                || normalized.contains("maximum context")
                || normalized.contains("context length")
                || normalized.contains("too many tokens")
                || normalized.contains("token limit");
    }

    public void markContextWindowFull(TurnContext turnContext) {
        state.setTokenUsageFull(modelContextWindowTokens());
        if (turnContext != null) {
            eventManager.emit(UiEvents.tokenUsage(turnContext, tokenUsageInfo(), currentContextTokenUsage(), autoCompactTokenLimit()));
        }
    }

    public void recomputeTokenUsageFromHistory(TurnContext turnContext) {
        recomputeTokenUsageFromHistory();
        if (turnContext != null) {
            eventManager.emit(UiEvents.tokenUsage(turnContext, tokenUsageInfo(), currentContextTokenUsage(), autoCompactTokenLimit()));
        }
    }

    public void recordUserMessage(UserMessage userMessage, TurnContext turnContext) {
        if (userMessage == null) {
            return;
        }
        recorder.record(userMessage, turnContext.turn());
        eventManager.emit(UiEvents.userMessage(userMessage, turnContext));
    }

    public void recordContextMessage(ContextMessage contextMessage, TurnContext turnContext) {
        if (contextMessage == null) {
            return;
        }
        recorder.record(contextMessage, turnContext.turn());
        eventManager.emit(UiEvents.contextMessage(contextMessage, turnContext));
    }

    public void recordAssistant(AssistantMessage assistantMessage, TurnContext turnContext) {
        if (assistantMessage == null) {
            return;
        }
        recorder.record(assistantMessage, turnContext.turn());
    }

    public void recordToolResult(
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext
    ) {
        recordToolResult(null, null, toolCall, toolResult, turnContext, null, null);
    }

    public void recordToolResult(
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext,
            String status,
            Long durationMs
    ) {
        if (toolResult == null) {
            return;
        }
        recorder.record(toolResult, turnContext.turn());
        eventManager.emit(UiEvents.toolExecutionFinished(sourceItemId, contentIndex, toolCall, toolResult, status, durationMs, turnContext));
        eventManager.emit(UiEvents.toolResult(sourceItemId, contentIndex, toolCall, toolResult, status, durationMs, turnContext));
    }

    public ToolResultMessage toolResultMessage(ToolCallContent toolCall, ToolExecutionResult result) {
        return contextBuilder.toolResultMessage(toolCall, result);
    }

    public ApprovalResponse requestApproval(ApprovalRequest request, TurnContext turnContext) {
        if (request == null) {
            return null;
        }
        eventManager.emit(UiEvents.approvalRequested(request, turnContext));

        ApprovalResponse response = approvalHandler.requestApproval(request);
        if (response == null || !request.id().equals(response.id())) {
            response = ApprovalResponse.deny(request.id(), "Approval response did not match the request.");
        }

        eventManager.emit(UiEvents.approvalResolved(request, response, turnContext));
        return response;
    }

    public void recordInitialContextIfChanged(TurnContext turnContext) {
        InitialContextSnapshot previous = state.initialContextBaseline();
        InitialContextSnapshot current = contextBuilder.initialContextSnapshot(config, turnContext);
        contextBuilder.initialContextMessages(previous, current)
                .forEach(message -> recordContextMessage(message, turnContext));
        state.setInitialContextBaseline(current);
        recordTurnContextBaseline(turnContext, current);
    }

    public List<ContextMessage> fullInitialContextMessages(TurnContext turnContext) {
        InitialContextSnapshot current = contextBuilder.initialContextSnapshot(config, turnContext);
        return contextBuilder.fullInitialContextMessages(current);
    }

    public void clearInitialContextBaseline() {
        state.setInitialContextBaseline(null);
    }

    public void markInitialContextBaseline(TurnContext turnContext) {
        InitialContextSnapshot current = contextBuilder.initialContextSnapshot(config, turnContext);
        state.setInitialContextBaseline(current);
        recordTurnContextBaseline(turnContext, current);
    }

    private void recordTurnContextBaseline(
            TurnContext turnContext,
            InitialContextSnapshot initialContextBaseline
    ) {
        if (turnContext == null || initialContextBaseline == null) {
            return;
        }
        recorder.recordTurnContext(TurnContextItem.builder()
                .turnId(turnContext.turnId() == null ? null : turnContext.turnId().value())
                .turn(turnContext.turn())
                .initialContextBaseline(initialContextBaseline)
                .build());
    }

    public AssistantMessage sampleModel(
            LlmClientSession modelSession,
            Prompt prompt,
            TurnContext turnContext,
            ToolCancellationToken cancellationToken
    ) {
        return sampleModelItems(modelSession, prompt, turnContext, cancellationToken, null);
    }

    public AssistantMessage sampleModelItems(
            LlmClientSession modelSession,
            Prompt prompt,
            TurnContext turnContext,
            ToolCancellationToken cancellationToken,
            Consumer<AssistantStreamEvent> itemConsumer
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
                        if (itemConsumer != null) {
                            itemConsumer.accept(event);
                        }
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
        recordTokenUsage(assistantMessage, turnContext);
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

    @Override
    public void close() {
        cancelRunningTask();
        try {
            waitForIdle(Duration.ofSeconds(5));
        } finally {
            if (skillsWatcher != null) {
                skillsWatcher.close();
            }
            eventManager.close();
        }
    }

    private void runRegularTask(MaterializedInput materializedInput) {
        runRegularTask(materializedInput, null);
    }

    private void runRegularTask(MaterializedInput materializedInput, String commandId) {
        runSessionTask(new RegularTask(), materializedInput, commandId);
    }

    private void runSessionTask(SessionTask task) {
        runSessionTask(task, null, null);
    }

    private void runSessionTask(SessionTask task, MaterializedInput materializedInput, String commandId) {
        ToolCancellationSource cancellationSource = new ToolCancellationSource();
        int turn;
        synchronized (this) {
            ensureIdle();
            state.markRunning();
            turn = state.nextTurn();
        }
        TurnContext turnContext = new TurnContext(TurnId.create(), sessionId(), turn, config.cwd(), commandId);

        eventManager.emit(UiEvents.turnStarted(turnContext));
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
            eventManager.emit(UiEvents.error(sessionId(), turn, e.getMessage()));
            eventManager.emit(UiEvents.turnAborted(turnContext));
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
                eventManager.emit(UiEvents.error(turnContext, e.getMessage()));
            }
        } finally {
            boolean cancelled = cancellationSource.token().isCancellationRequested();
            if (cancelled) {
                try {
                    recordInterruptedTurn(turnContext);
                } catch (RuntimeException e) {
                    eventManager.emit(UiEvents.error(turnContext, "Failed to record interrupted turn: " + e.getMessage()));
                }
            }
            if (cancelled) {
                eventManager.emit(UiEvents.turnAborted(turnContext));
            } else {
                eventManager.emit(UiEvents.turnCompleted(turnContext));
            }
            synchronized (this) {
                state.markIdle();
                notifyAll();
            }
        }
    }

    private void recordInterruptedTurn(TurnContext turnContext) {
        recordContextMessage(contextBuilder.interruptedTurnMessage(), turnContext);
    }

    private SkillsWatcher startSkillsWatcher() {
        try {
            SkillsWatcher watcher = new SkillsWatcher(skillsManager, this::emitSkillsChanged);
            watcher.start();
            return watcher;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void emitSkillsChanged(int availableSkillCount) {
        eventManager.emit(UiEvents.skillsChanged(sessionId(), availableSkillCount));
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

    private void recordTokenUsage(AssistantMessage assistantMessage, TurnContext turnContext) {
        if (assistantMessage == null || assistantMessage.getStopReason() == AssistantMessage.StopReason.ABORTED) {
            return;
        }
        TokenUsage usage = TokenUsage.fromUsageMap(assistantMessage.getUsage());
        if (usage.isEmpty()) {
            return;
        }
        state.updateTokenUsage(usage, modelContextWindowTokens());
        if (turnContext != null) {
            eventManager.emit(UiEvents.tokenUsage(turnContext, tokenUsageInfo(), currentContextTokenUsage(), autoCompactTokenLimit()));
        }
    }

    private void recomputeTokenUsageFromHistory() {
        state.recomputeTokenUsage(
                contextManager.estimateTokensForModel(config.systemPrompt()),
                modelContextWindowTokens()
        );
    }

    private Long modelContextWindowTokens() {
        LlmModel model = config.model();
        return model == null ? null : model.getContextWindowTokens();
    }

    private Long autoCompactTokenLimit() {
        LlmModel model = config.model();
        return model == null ? null : model.resolvedAutoCompactTokenLimit();
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
