package io.github.lingjiuu.transcript;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;

public class TranscriptRestorer {

    private final TranscriptStore store;
    private final TranscriptChainBuilder chainBuilder;
    private final TranscriptProjector projector;

    public TranscriptRestorer(TranscriptStore store) {
        this(store, new TranscriptChainBuilder(), new TranscriptProjector());
    }

    public TranscriptRestorer(TranscriptStore store, TranscriptChainBuilder chainBuilder) {
        this(store, chainBuilder, new TranscriptProjector());
    }

    public TranscriptRestorer(
            TranscriptStore store,
            TranscriptChainBuilder chainBuilder,
            TranscriptProjector projector
    ) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (chainBuilder == null) {
            throw new IllegalArgumentException("chainBuilder must not be null");
        }
        if (projector == null) {
            throw new IllegalArgumentException("projector must not be null");
        }
        this.store = store;
        this.chainBuilder = chainBuilder;
        this.projector = projector;
    }

    public RestoredTranscript restore(String sessionId) {
        TranscriptChain chain = chainBuilder.build(store.read(sessionId));
        TranscriptProjection projection = projector.project(chain);
        return new RestoredTranscript(
                sessionId,
                chain,
                new AgentRuntimeState(projection.messages()),
                chain.lastRecordId()
        );
    }
}
