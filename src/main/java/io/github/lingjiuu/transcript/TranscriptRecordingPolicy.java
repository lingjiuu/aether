package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;

public class TranscriptRecordingPolicy {

    public boolean shouldRecord(Message message) {
        return message != null && isSupportedRole(message.role());
    }

    public boolean participatesInChain(Message message) {
        return shouldRecord(message);
    }

    public boolean canAnchorResume(TranscriptRecord record) {
        return record != null && shouldRecord(record.getMessage());
    }

    private boolean isSupportedRole(Message.Role role) {
        return role == Message.Role.USER
                || role == Message.Role.ASSISTANT
                || role == Message.Role.TOOLRESULT
                || role == Message.Role.SYSTEM
                || role == Message.Role.ATTACHMENT;
    }
}
