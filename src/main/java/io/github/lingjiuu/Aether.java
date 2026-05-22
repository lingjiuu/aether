package io.github.lingjiuu;

import io.github.lingjiuu.command.CommandManager;
import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.event.EventSubscription;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiEventPage;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiHistory;
import io.github.lingjiuu.protocol.UiSessionSummary;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.tool.permission.ApprovalHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Aether implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Aether.class.getName());

    private final CommandManager commands;
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();

    public Aether(SessionFactory sessionFactory) {
        this(sessionFactory, null, null);
    }

    public Aether(
            SessionFactory sessionFactory,
            EventSink eventSink,
            ApprovalHandler approvalHandler
    ) {
        if (eventSink != null) {
            sinks.add(eventSink);
        }
        commands = new CommandManager(sessionFactory, this::dispatch, approvalHandler);
    }

    public UiCommandAck submit(UiCommand command) {
        return commands.handle(command);
    }

    public EventSubscription subscribe(EventSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.add(sink);
        return () -> sinks.remove(sink);
    }

    public UiHistory history() {
        return commands.history();
    }

    public List<UiEvent> eventsAfter(long sequence) {
        return currentSession().eventsAfter(sequence);
    }

    public UiEventPage eventPage(long afterSequence, int limit) {
        List<UiEvent> all = currentSession().eventsAfter(afterSequence);
        int safeLimit = limit <= 0 ? 200 : limit;
        boolean hasMore = all.size() > safeLimit;
        List<UiEvent> page = hasMore ? all.subList(0, safeLimit) : all;
        long nextAfterSequence = afterSequence;
        for (UiEvent event : page) {
            if (event != null && event.getSequence() != null) {
                nextAfterSequence = Math.max(nextAfterSequence, event.getSequence());
            }
        }
        return new UiEventPage(
                sessionId(),
                afterSequence,
                page,
                nextAfterSequence,
                hasMore,
                false
        );
    }

    public List<UiSessionSummary> listSessions() {
        return commands.listSessions();
    }

    public String sessionId() {
        return commands.sessionId();
    }

    public int messageCount() {
        return currentSession().messages().size();
    }

    public List<Skill> availableSkills() {
        return currentSession().availableSkills();
    }

    public void waitForIdle() {
        currentSession().waitForIdle();
    }

    public boolean waitForIdle(Duration timeout) {
        return currentSession().waitForIdle(timeout);
    }

    @Override
    public void close() {
        commands.close();
    }

    Session currentSession() {
        return commands.currentSession();
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
}
