package io.github.lingjiuu.event;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.llm.TokenUsageInfo;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.transcript.TranscriptRecorder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(EventManager.class.getName());

    private final UiEventBuilder eventBuilder = new UiEventBuilder();
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final List<UiEvent> timeline;
    private final TranscriptRecorder transcriptRecorder;
    private final AtomicLong sequence;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "aether-ui-events");
        thread.setDaemon(true);
        return thread;
    });

    public EventManager() {
        this(null, List.of(), 0);
    }

    public EventManager(
            TranscriptRecorder transcriptRecorder,
            List<UiEvent> initialTimeline,
            long initialSequence
    ) {
        this.transcriptRecorder = transcriptRecorder;
        List<UiEvent> seedTimeline = initialTimeline == null ? List.of() : initialTimeline;
        this.timeline = new CopyOnWriteArrayList<>(seedTimeline);
        this.sequence = new AtomicLong(Math.max(initialSequence, maxSequence(seedTimeline)));
    }

    public EventSubscription subscribe(EventSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.add(sink);
        return () -> sinks.remove(sink);
    }

    public List<UiEvent> timelineEvents() {
        return List.copyOf(timeline);
    }

    public void runStarted(String sessionId, int turn) {
        emit(eventBuilder.runStarted(sessionId, turn));
    }

    public void runFinished(String sessionId, int turn) {
        emit(eventBuilder.runFinished(sessionId, turn));
    }

    public void turnStarted(TurnContext turnContext) {
        emit(eventBuilder.turnStarted(turnContext));
    }

    public void turnAborted(TurnContext turnContext) {
        emit(eventBuilder.turnAborted(turnContext));
    }

    public void sessionReset(String sessionId) {
        emit(eventBuilder.sessionReset(sessionId));
    }

    public void skillsChanged(String sessionId, int availableSkillCount) {
        emit(eventBuilder.skillsChanged(sessionId, availableSkillCount));
    }

    public void userMessage(UserMessage userMessage, TurnContext turnContext) {
        emit(eventBuilder.userMessage(userMessage, turnContext));
    }

    public void contextMessage(ContextMessage contextMessage, TurnContext turnContext) {
        emit(eventBuilder.contextMessage(contextMessage, turnContext));
    }

    public void itemStarted(
            TurnContext turnContext,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall
    ) {
        emit(eventBuilder.itemStarted(turnContext, itemKind, itemId, contentIndex, toolCall));
    }

    public void itemCompleted(
            TurnContext turnContext,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            String text
    ) {
        emit(eventBuilder.itemCompleted(turnContext, itemKind, itemId, contentIndex, toolCall, text));
    }

    public void assistantTextDelta(TurnContext turnContext, String itemId, Integer contentIndex, String delta) {
        emit(eventBuilder.assistantTextDelta(turnContext, itemId, contentIndex, delta));
    }

    public void reasoningDelta(TurnContext turnContext, String itemId, Integer contentIndex, String delta) {
        emit(eventBuilder.reasoningDelta(turnContext, itemId, contentIndex, delta));
    }

    public void toolArgumentsDelta(
            TurnContext turnContext,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            String delta
    ) {
        emit(eventBuilder.toolArgumentsDelta(turnContext, itemId, contentIndex, toolCall, delta));
    }

    public void toolArgumentsDone(
            TurnContext turnContext,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall
    ) {
        emit(eventBuilder.toolArgumentsDone(turnContext, itemId, contentIndex, toolCall));
    }

    public void toolCall(ToolCallContent toolCall, TurnContext turnContext) {
        emit(eventBuilder.toolCall(toolCall, turnContext));
    }

    public void toolExecutionStarted(ToolCallContent toolCall, TurnContext turnContext) {
        emit(eventBuilder.toolExecutionStarted(toolCall, turnContext));
    }

    public void toolExecutionUpdate(
            ToolCallContent toolCall,
            ToolExecutionResult partialResult,
            TurnContext turnContext
    ) {
        emit(eventBuilder.toolExecutionUpdate(toolCall, partialResult, turnContext));
    }

    public void toolExecutionFinished(
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext
    ) {
        emit(eventBuilder.toolExecutionFinished(toolCall, toolResult, turnContext));
    }

    public void toolResult(
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext
    ) {
        emit(eventBuilder.toolResult(toolCall, toolResult, turnContext));
    }

    public void finalAnswer(AssistantMessage assistantMessage, TurnContext turnContext) {
        emit(eventBuilder.finalAnswer(assistantMessage, turnContext));
    }

    public void error(TurnContext turnContext, String message) {
        emit(eventBuilder.error(turnContext, message));
    }

    public void error(String sessionId, int turn, String message) {
        emit(eventBuilder.error(sessionId, turn, message));
    }

    public void approvalRequested(ApprovalRequest request, TurnContext turnContext) {
        emit(eventBuilder.approvalRequested(request, turnContext));
    }

    public void approvalResolved(ApprovalRequest request, ApprovalResponse response, TurnContext turnContext) {
        emit(eventBuilder.approvalResolved(request, response, turnContext));
    }

    public void tokenUsage(
            TurnContext turnContext,
            TokenUsageInfo tokenUsageInfo,
            long contextTokenUsage,
            Long autoCompactTokenLimit
    ) {
        emit(eventBuilder.tokenUsage(turnContext, tokenUsageInfo, contextTokenUsage, autoCompactTokenLimit));
    }

    public void compactStarted(TurnContext turnContext, String trigger, int originalMessageCount) {
        emit(eventBuilder.compactStarted(turnContext, trigger, originalMessageCount));
    }

    public void compactSkipped(
            TurnContext turnContext,
            String text,
            int originalMessageCount,
            int replacementMessageCount
    ) {
        emit(eventBuilder.compactSkipped(turnContext, text, originalMessageCount, replacementMessageCount));
    }

    public void compactFinished(
            TurnContext turnContext,
            String summary,
            int originalMessageCount,
            int replacementMessageCount
    ) {
        emit(eventBuilder.compactFinished(turnContext, summary, originalMessageCount, replacementMessageCount));
    }

    public void emit(UiEvent event) {
        if (event == null) {
            return;
        }
        try {
            dispatcher.execute(() -> {
                event.stamp(sequence.incrementAndGet(), System.currentTimeMillis());
                persist(event);
                timeline.add(event);
                dispatch(event);
            });
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.FINE, "UI event queue is closed; dropping event " + event.getType(), e);
        }
    }

    public void flush() {
        try {
            Future<?> future = dispatcher.submit(() -> {
            });
            future.get();
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.FINE, "UI event queue is closed; skipping flush.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to flush UI event queue.", e);
        }
    }

    @Override
    public void close() {
        flush();
        dispatcher.shutdownNow();
    }

    private void dispatch(UiEvent event) {
        for (EventSink sink : sinks) {
            try {
                sink.onEvent(event);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to dispatch UI event to sink " + sink, e);
            }
        }
    }

    private void persist(UiEvent event) {
        if (transcriptRecorder == null) {
            return;
        }
        try {
            transcriptRecorder.recordEvent(event);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to persist UI event " + event.getType(), e);
        }
    }

    private long maxSequence(List<UiEvent> events) {
        long max = 0;
        for (UiEvent event : events) {
            if (event != null && event.getSequence() != null) {
                max = Math.max(max, event.getSequence());
            }
        }
        return max;
    }
}
