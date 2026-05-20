package io.github.lingjiuu.session;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.util.List;
import java.util.UUID;

public class SessionBuilder {

    private SessionConfig config;
    private ToolRegistry toolRegistry;
    private String sessionId = UUID.randomUUID().toString();
    private List<Message> initialMessages = List.of();
    private String lastTranscriptRecordId;
    private SessionMetaItem sessionMeta;
    private boolean recordSessionMeta;

    public SessionBuilder config(SessionConfig config) {
        this.config = config;
        return this;
    }

    public SessionBuilder toolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        return this;
    }

    public SessionBuilder sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public SessionBuilder initialMessages(List<Message> initialMessages) {
        this.initialMessages = initialMessages == null ? List.of() : List.copyOf(initialMessages);
        return this;
    }

    public SessionBuilder lastTranscriptRecordId(String lastTranscriptRecordId) {
        this.lastTranscriptRecordId = lastTranscriptRecordId;
        return this;
    }

    public SessionBuilder sessionMeta(SessionMetaItem sessionMeta) {
        this.sessionMeta = sessionMeta;
        return this;
    }

    public SessionBuilder recordSessionMeta(boolean recordSessionMeta) {
        this.recordSessionMeta = recordSessionMeta;
        return this;
    }

    public Session build() {
        if (config == null) {
            throw new IllegalStateException("session config must be provided.");
        }
        ToolRegistry registry = toolRegistry == null ? new ToolRegistry() : toolRegistry;
        for (ToolDefinition definition : config.toolDefinitions()) {
            registry.register(definition);
        }
        return new Session(config, registry, sessionId, initialMessages, lastTranscriptRecordId, sessionMeta, recordSessionMeta);
    }
}
