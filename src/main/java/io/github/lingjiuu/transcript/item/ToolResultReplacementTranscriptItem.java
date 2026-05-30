package io.github.lingjiuu.transcript.item;

import io.github.lingjiuu.message.ToolResultMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultReplacementTranscriptItem implements TranscriptItem {

    private String messageId;

    private String toolCallId;

    private String toolBatchId;

    private ToolResultMessage replacementMessage;

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.TOOL_RESULT_REPLACEMENT;
    }
}
