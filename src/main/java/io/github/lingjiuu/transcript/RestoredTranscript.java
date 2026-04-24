package io.github.lingjiuu.transcript;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RestoredTranscript {

    private String sessionId;

    private TranscriptChain chain;

    private AgentRuntimeState runtimeState;

    private String lastRecordId;
}
