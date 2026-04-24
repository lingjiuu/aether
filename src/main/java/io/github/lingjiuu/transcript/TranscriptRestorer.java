package io.github.lingjiuu.transcript;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;

public class TranscriptRestorer {

    private final TranscriptStore store;
    private final TranscriptChainBuilder chainBuilder;

    public TranscriptRestorer(TranscriptStore store) {
        this(store, new TranscriptChainBuilder());
    }

    public TranscriptRestorer(TranscriptStore store, TranscriptChainBuilder chainBuilder) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (chainBuilder == null) {
            throw new IllegalArgumentException("chainBuilder must not be null");
        }
        this.store = store;
        this.chainBuilder = chainBuilder;
    }

    public RestoredTranscript restore(String sessionId) {
        TranscriptChain chain = chainBuilder.build(store.read(sessionId));
        return new RestoredTranscript(
                sessionId,
                chain,
                new AgentRuntimeState(chain.messages()),
                chain.lastRecordId()
        );
    }
}
