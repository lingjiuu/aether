package io.github.lingjiuu.ui;

import io.github.lingjiuu.Aether;
import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.event.EventSubscription;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiEventPage;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiHistory;
import io.github.lingjiuu.protocol.UiSessionSummary;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.tool.permission.ApprovalHandler;

import java.util.List;

public class UiRuntime implements AutoCloseable {

    private final Aether aether;

    public UiRuntime(
            SessionFactory sessionFactory,
            EventSink eventSink,
            ApprovalHandler approvalHandler
    ) {
        aether = new Aether(sessionFactory, eventSink, approvalHandler);
    }

    public UiCommandAck submit(UiCommand command) {
        return aether.submit(command);
    }

    public EventSubscription subscribe(EventSink sink) {
        return aether.subscribe(sink);
    }

    public UiHistory history() {
        return aether.history();
    }

    public List<UiEvent> eventsAfter(long sequence) {
        return aether.eventsAfter(sequence);
    }

    public UiEventPage eventPage(long afterSequence, int limit) {
        return aether.eventPage(afterSequence, limit);
    }

    public List<UiSessionSummary> listSessions() {
        return aether.listSessions();
    }

    public void waitForIdle() {
        aether.waitForIdle();
    }

    public int messageCount() {
        return aether.messageCount();
    }

    public List<Skill> availableSkills() {
        return aether.availableSkills();
    }

    public String sessionId() {
        return aether.sessionId();
    }

    @Override
    public void close() {
        aether.close();
    }
}
