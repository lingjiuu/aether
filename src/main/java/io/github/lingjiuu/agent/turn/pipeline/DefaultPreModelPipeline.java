package io.github.lingjiuu.agent.turn.pipeline;

import io.github.lingjiuu.attachment.AttachmentProjectionStep;
import io.github.lingjiuu.compact.snip.SnipStep;
import io.github.lingjiuu.compact.toolbudget.ToolResultBudgetStep;
import io.github.lingjiuu.message.Message;

import java.util.ArrayList;
import java.util.List;

public class DefaultPreModelPipeline implements PreModelPipeline {

    private final List<PreModelStep> steps;

    public DefaultPreModelPipeline() {
        this(List.of(
                new ToolResultBudgetStep(),
                new SnipStep(),
                new AttachmentProjectionStep()
        ));
    }

    public DefaultPreModelPipeline(List<PreModelStep> steps) {
        if (steps == null) {
            throw new IllegalArgumentException("steps must not be null");
        }
        this.steps = List.copyOf(steps);
    }

    @Override
    public PreModelPipelineResult apply(PreModelContext context, List<Message> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        List<Message> current = List.copyOf(messages);
        List<Message> recordedMessages = new ArrayList<>();
        for (PreModelStep step : steps) {
            PreModelStepResult result = step.apply(context, current);
            current = new ArrayList<>(result.messages());
            if (!result.recordedMessages().isEmpty()) {
                current.addAll(result.recordedMessages());
                recordedMessages.addAll(result.recordedMessages());
            }
        }
        return new PreModelPipelineResult(current, recordedMessages);
    }
}
