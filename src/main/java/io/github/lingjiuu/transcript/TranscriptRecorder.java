package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;

import java.util.UUID;

public class TranscriptRecorder {

    private final TranscriptStore store;
    private final TranscriptRecordingPolicy policy;
    private final String sessionId;
    private String parentRecordId;

    public TranscriptRecorder(TranscriptStore store, String sessionId) {
        this(store, new TranscriptRecordingPolicy(), sessionId, null);
    }

    public TranscriptRecorder(TranscriptStore store, String sessionId, String parentRecordId) {
        this(store, new TranscriptRecordingPolicy(), sessionId, parentRecordId);
    }

    public TranscriptRecorder(
            TranscriptStore store,
            TranscriptRecordingPolicy policy,
            String sessionId,
            String parentRecordId
    ) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.store = store;
        this.policy = policy;
        this.sessionId = sessionId;
        this.parentRecordId = parentRecordId;
    }

    public synchronized TranscriptRecord record(Message message, int turn) {
        if (!policy.shouldRecord(message)) {
            return null;
        }

        boolean participatesInChain = policy.participatesInChain(message);
        TranscriptRecord record = TranscriptRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .parentRecordId(participatesInChain ? parentRecordId : null)
                .turn(turn)
                .timestamp(System.currentTimeMillis())
                .message(message)
                .build();

        store.append(record);
        if (participatesInChain) {
            parentRecordId = record.getId();
        }
        return record;
    }

    public synchronized String lastRecordId() {
        return parentRecordId;
    }

    public synchronized void resetParent(String parentRecordId) {
        this.parentRecordId = parentRecordId;
    }
}
