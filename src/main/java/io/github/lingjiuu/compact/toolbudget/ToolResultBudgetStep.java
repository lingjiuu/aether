package io.github.lingjiuu.compact.toolbudget;

import io.github.lingjiuu.agent.turn.pipeline.PreModelContext;
import io.github.lingjiuu.agent.turn.pipeline.PreModelStep;
import io.github.lingjiuu.agent.turn.pipeline.PreModelStepResult;
import io.github.lingjiuu.compact.TokenEstimator;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolResultBudgetStep implements PreModelStep {

    private static final String REPLACEMENT_TAG = "[Aether tool result stored outside active context]";

    private final ToolResultBudgetPolicy policy;
    private final TokenEstimator tokenEstimator;
    private final ToolResultContentReplacer contentReplacer;

    public ToolResultBudgetStep() {
        this(ToolResultBudgetPolicy.defaultPolicy(), new TokenEstimator(), new ToolResultContentReplacer());
    }

    public ToolResultBudgetStep(ToolResultBudgetPolicy policy) {
        this(policy, new TokenEstimator(), new ToolResultContentReplacer());
    }

    public ToolResultBudgetStep(
            ToolResultBudgetPolicy policy,
            TokenEstimator tokenEstimator,
            ToolResultContentReplacer contentReplacer
    ) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (tokenEstimator == null) {
            throw new IllegalArgumentException("tokenEstimator must not be null");
        }
        if (contentReplacer == null) {
            throw new IllegalArgumentException("contentReplacer must not be null");
        }
        this.policy = policy;
        this.tokenEstimator = tokenEstimator;
        this.contentReplacer = contentReplacer;
    }

    @Override
    public PreModelStepResult apply(PreModelContext context, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return PreModelStepResult.unchanged(messages);
        }

        ToolResultBudgetState state = new ToolResultBudgetState(ToolResultReplacementIndex.fromMessages(messages));
        Map<String, String> replacementsToApply = new LinkedHashMap<>(state.replacements());
        List<SystemMessage.ToolResultReplacement> newlyReplaced = new ArrayList<>();

        for (List<ToolResultCandidate> group : collectGroups(messages)) {
            int groupSize = 0;
            List<ToolResultCandidate> fresh = new ArrayList<>();
            for (ToolResultCandidate candidate : group) {
                String existingReplacement = state.replacementFor(candidate.toolCallId());
                if (existingReplacement != null) {
                    replacementsToApply.put(candidate.toolCallId(), existingReplacement);
                    groupSize += existingReplacement.length();
                    continue;
                }
                groupSize += candidate.size();
                if (!candidate.content().startsWith(REPLACEMENT_TAG)) {
                    fresh.add(candidate);
                }
            }
            if (groupSize <= policy.maxCharsPerGroup()) {
                continue;
            }
            fresh.sort(Comparator.comparingInt(ToolResultCandidate::size).reversed());
            int remaining = groupSize;
            for (ToolResultCandidate candidate : fresh) {
                if (remaining <= policy.maxCharsPerGroup()) {
                    break;
                }
                String replacement = buildReplacement(candidate);
                state.put(candidate.toolCallId(), replacement);
                replacementsToApply.put(candidate.toolCallId(), replacement);
                newlyReplaced.add(new SystemMessage.ToolResultReplacement(candidate.toolCallId(), replacement));
                remaining -= Math.max(0, candidate.size() - replacement.length());
            }
        }

        List<Message> replacedMessages = contentReplacer.replace(messages, replacementsToApply);
        if (newlyReplaced.isEmpty()) {
            return new PreModelStepResult(replacedMessages, List.of());
        }
        SystemMessage marker = SystemMessage.builder()
                .subtype(SystemMessage.Subtype.CONTENT_REPLACEMENT)
                .contents(List.of(TextContent.builder()
                        .text("Tool result content was replaced with stable previews for the active context.")
                        .build()))
                .toolResultReplacements(newlyReplaced)
                .build();
        return new PreModelStepResult(replacedMessages, List.of(marker));
    }

    private List<List<ToolResultCandidate>> collectGroups(List<Message> messages) {
        List<List<ToolResultCandidate>> groups = new ArrayList<>();
        List<ToolResultCandidate> current = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage) {
                flush(groups, current);
                current = new ArrayList<>();
                continue;
            }
            if (message instanceof ToolResultMessage toolResultMessage) {
                String content = MessageContents.text(toolResultMessage);
                if (!content.isBlank() && toolResultMessage.getToolCallId() != null) {
                    current.add(new ToolResultCandidate(
                            toolResultMessage.getToolCallId(),
                            content,
                            (int) tokenEstimator.estimateChars(toolResultMessage)
                    ));
                }
            }
        }
        flush(groups, current);
        return groups;
    }

    private void flush(List<List<ToolResultCandidate>> groups, List<ToolResultCandidate> current) {
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
    }

    private String buildReplacement(ToolResultCandidate candidate) {
        String preview = preview(candidate.content(), policy.previewChars());
        return REPLACEMENT_TAG + "\n"
                + "tool_call_id: " + candidate.toolCallId() + "\n"
                + "original_chars: " + candidate.size() + "\n"
                + "preview:\n" + preview;
    }

    private String preview(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        String truncated = content.substring(0, maxChars);
        int lastNewline = truncated.lastIndexOf('\n');
        int cutPoint = lastNewline > maxChars / 2 ? lastNewline : maxChars;
        return content.substring(0, cutPoint) + "\n[...truncated...]";
    }

    private record ToolResultCandidate(
            String toolCallId,
            String content,
            int size
    ) {
    }
}
