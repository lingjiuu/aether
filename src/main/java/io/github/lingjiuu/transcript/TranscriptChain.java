package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;

import java.util.List;

public class TranscriptChain {

    private final List<TranscriptRecord> records;

    public TranscriptChain(List<TranscriptRecord> records) {
        this.records = records == null ? List.of() : List.copyOf(records);
    }

    public static TranscriptChain empty() {
        return new TranscriptChain(List.of());
    }

    public List<TranscriptRecord> records() {
        return records;
    }

    public List<Message> messages() {
        return records.stream()
                .map(TranscriptRecord::getMessage)
                .toList();
    }

    public String lastRecordId() {
        return records.isEmpty() ? null : records.getLast().getId();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
