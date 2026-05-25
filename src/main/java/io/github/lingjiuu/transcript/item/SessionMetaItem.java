package io.github.lingjiuu.transcript.item;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaItem implements TranscriptItem {

    private String sessionId;

    private long createdAt;

    private String cwd;

    private String modelProvider;

    private String modelId;

    private String modelApi;

    private String modelBaseUrl;

    private Long modelContextWindowTokens;

    private Long modelAutoCompactTokenLimit;

    private String reasoningEffort;

    private String systemPromptHash;

    @Builder.Default
    private List<String> activeToolNames = List.of();

    private String configFingerprint;

    private String aetherVersion;

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.SESSION_META;
    }
}
