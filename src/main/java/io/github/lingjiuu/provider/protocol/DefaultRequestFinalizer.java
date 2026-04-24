package io.github.lingjiuu.provider.protocol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DefaultRequestFinalizer implements RequestFinalizer {

    private static final String EMPTY_ASSISTANT_PLACEHOLDER = "[No assistant content]";

    @Override
    public List<NormalizedRequestMessage> finalizeMessages(List<NormalizedRequestMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<NormalizedRequestMessage> result = new ArrayList<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();
        Set<String> pendingToolCallIds = new LinkedHashSet<>();
        boolean previousAssistantHadReplayData = false;

        for (NormalizedRequestMessage message : messages) {
            if (message == null) {
                continue;
            }

            if (message instanceof NormalizedAssistantMessage assistantMessage) {
                addMissingToolResults(result, pendingToolCallIds);
                NormalizedAssistantMessage finalizedAssistant = finalizeAssistant(assistantMessage, seenToolCallIds);
                result.add(finalizedAssistant);
                previousAssistantHadReplayData = assistantMessage.hasProviderReplayData();
                if (!previousAssistantHadReplayData) {
                    pendingToolCallIds.addAll(toolCallIds(finalizedAssistant));
                }
                continue;
            }

            if (message instanceof NormalizedContextMessage contextMessage) {
                if (previousAssistantHadReplayData) {
                    result.add(contextMessage);
                    previousAssistantHadReplayData = false;
                    continue;
                }
                NormalizedContextMessage finalizedContext = finalizeContext(contextMessage, pendingToolCallIds);
                if (!finalizedContext.contents().isEmpty()) {
                    result.add(finalizedContext);
                }
                previousAssistantHadReplayData = false;
                continue;
            }

            addMissingToolResults(result, pendingToolCallIds);
            result.add(message);
            previousAssistantHadReplayData = false;
        }

        addMissingToolResults(result, pendingToolCallIds);
        return List.copyOf(result);
    }

    private NormalizedAssistantMessage finalizeAssistant(
            NormalizedAssistantMessage assistantMessage,
            Set<String> seenToolCallIds
    ) {
        if (assistantMessage.hasProviderReplayData()) {
            return assistantMessage;
        }

        List<NormalizedContent> contents = new ArrayList<>();
        for (NormalizedContent content : assistantMessage.contents()) {
            if (content instanceof NormalizedToolCallContent toolCallContent) {
                String toolCallId = toolCallContent.toolCallId();
                if (!toolCallId.isBlank() && seenToolCallIds.contains(toolCallId)) {
                    continue;
                }
                if (!toolCallId.isBlank()) {
                    seenToolCallIds.add(toolCallId);
                }
            }
            contents.add(content);
        }

        if (contents.isEmpty()) {
            contents.add(new NormalizedTextContent(EMPTY_ASSISTANT_PLACEHOLDER));
        }
        return assistantMessage.withContents(contents);
    }

    private NormalizedContextMessage finalizeContext(
            NormalizedContextMessage contextMessage,
            Set<String> pendingToolCallIds
    ) {
        List<NormalizedContent> contents = new ArrayList<>();
        Set<String> seenToolResultIds = new LinkedHashSet<>();

        for (NormalizedContent content : contextMessage.contents()) {
            if (content instanceof NormalizedToolResultContent toolResultContent) {
                String toolCallId = toolResultContent.toolCallId();
                if (toolCallId.isBlank()
                        || !pendingToolCallIds.contains(toolCallId)
                        || seenToolResultIds.contains(toolCallId)) {
                    continue;
                }
                pendingToolCallIds.remove(toolCallId);
                seenToolResultIds.add(toolCallId);
            }
            contents.add(content);
        }

        return contextMessage.withContents(contents);
    }

    private void addMissingToolResults(
            List<NormalizedRequestMessage> result,
            Set<String> pendingToolCallIds
    ) {
        if (pendingToolCallIds.isEmpty()) {
            return;
        }

        List<NormalizedContent> syntheticResults = new ArrayList<>();
        for (String toolCallId : pendingToolCallIds) {
            syntheticResults.add(NormalizedToolResultContent.syntheticError(toolCallId));
        }
        if (!result.isEmpty() && result.getLast() instanceof NormalizedContextMessage contextMessage) {
            List<NormalizedContent> mergedContents = new ArrayList<>(contextMessage.contents());
            mergedContents.addAll(syntheticResults);
            result.set(result.size() - 1, contextMessage.withContents(mergedContents));
        } else {
            result.add(new NormalizedContextMessage(syntheticResults));
        }
        pendingToolCallIds.clear();
    }

    private List<String> toolCallIds(NormalizedAssistantMessage assistantMessage) {
        List<String> toolCallIds = new ArrayList<>();
        for (NormalizedContent content : assistantMessage.contents()) {
            if (content instanceof NormalizedToolCallContent toolCallContent
                    && !toolCallContent.toolCallId().isBlank()) {
                toolCallIds.add(toolCallContent.toolCallId());
            }
        }
        return toolCallIds;
    }
}
