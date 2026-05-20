package io.github.lingjiuu.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class TranscriptStore {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path transcriptsDir;

    public TranscriptStore(Path transcriptsDir) {
        if (transcriptsDir == null) {
            throw new IllegalArgumentException("transcriptsDir must not be null");
        }
        this.transcriptsDir = transcriptsDir;
    }

    public Path transcriptsDir() {
        return transcriptsDir;
    }

    public Path pathForSession(String sessionId) {
        return transcriptsDir.resolve(safeSessionFileName(sessionId));
    }

    public synchronized void append(TranscriptRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        if (record.getSessionId() == null || record.getSessionId().isBlank()) {
            throw new IllegalArgumentException("record sessionId must not be blank");
        }

        try {
            Files.createDirectories(transcriptsDir);
            String line = objectMapper.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(
                    pathForSession(record.getSessionId()),
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            throw new TranscriptException("Failed to append transcript record.", e);
        }
    }

    public synchronized List<TranscriptRecord> read(String sessionId) {
        Path path = pathForSession(sessionId);
        if (!Files.exists(path)) {
            return List.of();
        }

        try {
            List<TranscriptRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                records.add(objectMapper.readValue(line, TranscriptRecord.class));
            }
            return List.copyOf(records);
        } catch (Exception e) {
            throw new TranscriptException("Failed to read transcript records for session: " + sessionId, e);
        }
    }

    public boolean exists(String sessionId) {
        return Files.exists(pathForSession(sessionId));
    }

    private String safeSessionFileName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl";
    }
}
