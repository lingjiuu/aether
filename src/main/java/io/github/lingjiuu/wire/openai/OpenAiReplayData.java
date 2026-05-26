package io.github.lingjiuu.wire.openai;

import io.github.lingjiuu.wire.WireReplayData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiReplayData implements WireReplayData {

    private String responseId;

    @Builder.Default
    private List<ReplayItem> items = new ArrayList<>();

    @Override
    public String provider() {
        return "openai";
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplayItem {
        private Type type;
        private String json;
    }

    public enum Type {
        OUTPUT_MESSAGE,
        REASONING,
        FUNCTION_CALL
    }
}
