package io.github.lingjiuu.compact.snip;

import io.github.lingjiuu.compact.TokenEstimator;
import io.github.lingjiuu.message.AttachmentMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.content.TextContent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SnipService {

    private final SnipPolicy policy;
    private final TokenEstimator tokenEstimator;

    public SnipService() {
        this(SnipPolicy.defaultPolicy(), new TokenEstimator());
    }

    public SnipService(SnipPolicy policy) {
        this(policy, new TokenEstimator());
    }

    public SnipService(SnipPolicy policy, TokenEstimator tokenEstimator) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (tokenEstimator == null) {
            throw new IllegalArgumentException("tokenEstimator must not be null");
        }
        this.policy = policy;
        this.tokenEstimator = tokenEstimator;
    }

    public SnipPlan snipIfNeeded(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new SnipPlan(List.of(), null, 0);
        }
        long totalTokens = tokenEstimator.estimateTokens(messages);
        if (totalTokens <= policy.maxActiveTokens()) {
            return new SnipPlan(messages, null, 0);
        }

        Set<Integer> keepIndexes = new LinkedHashSet<>();
        keepIndexes.add(0);
        for (int index = Math.max(0, messages.size() - policy.keepRecentMessages()); index < messages.size(); index++) {
            keepIndexes.add(index);
        }
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message instanceof SystemMessage || message instanceof AttachmentMessage) {
                keepIndexes.add(index);
            }
        }

        List<Message> kept = new ArrayList<>();
        List<String> removedIds = new ArrayList<>();
        long removedTokens = 0;
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (keepIndexes.contains(index)) {
                kept.add(message);
                continue;
            }
            removedIds.add(message.id());
            removedTokens += tokenEstimator.estimateTokens(List.of(message));
        }

        if (removedIds.size() < policy.minimumRemovedMessages()) {
            return new SnipPlan(messages, null, 0);
        }

        SystemMessage boundary = SystemMessage.builder()
                .subtype(SystemMessage.Subtype.SNIP_BOUNDARY)
                .contents(List.of(TextContent.builder()
                        .text("Older middle conversation messages were snipped from the active context.")
                        .build()))
                .removedMessageIds(removedIds)
                .estimatedTokensFreed(removedTokens)
                .build();
        return new SnipPlan(kept, boundary, removedTokens);
    }
}
