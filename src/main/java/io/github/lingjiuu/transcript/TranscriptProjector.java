package io.github.lingjiuu.transcript;

import io.github.lingjiuu.compact.snip.SnipProjection;
import io.github.lingjiuu.compact.toolbudget.ToolResultContentReplacer;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.SystemMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TranscriptProjector {

    private final ToolResultContentReplacer contentReplacer;
    private final SnipProjection snipProjection;

    public TranscriptProjector() {
        this(new ToolResultContentReplacer(), new SnipProjection());
    }

    public TranscriptProjector(ToolResultContentReplacer contentReplacer, SnipProjection snipProjection) {
        if (contentReplacer == null) {
            throw new IllegalArgumentException("contentReplacer must not be null");
        }
        if (snipProjection == null) {
            throw new IllegalArgumentException("snipProjection must not be null");
        }
        this.contentReplacer = contentReplacer;
        this.snipProjection = snipProjection;
    }

    public TranscriptProjection project(TranscriptChain chain) {
        if (chain == null || chain.isEmpty()) {
            return new TranscriptProjection(List.of());
        }
        List<Message> current = new ArrayList<>();
        for (TranscriptRecord record : chain.records()) {
            Message message = record.getMessage();
            if (message == null) {
                continue;
            }
            current.add(message);
            if (message instanceof SystemMessage systemMessage) {
                if (systemMessage.getSubtype() == SystemMessage.Subtype.CONTENT_REPLACEMENT) {
                    current = new ArrayList<>(contentReplacer.replace(current, replacements(systemMessage)));
                } else if (systemMessage.getSubtype() == SystemMessage.Subtype.SNIP_BOUNDARY) {
                    current = new ArrayList<>(snipProjection.apply(current));
                }
            }
        }
        return new TranscriptProjection(current);
    }

    private Map<String, String> replacements(SystemMessage systemMessage) {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (SystemMessage.ToolResultReplacement replacement : systemMessage.getToolResultReplacements()) {
            if (replacement.toolCallId() != null && replacement.replacement() != null) {
                replacements.put(replacement.toolCallId(), replacement.replacement());
            }
        }
        return replacements;
    }
}
