package io.github.lingjiuu.compact.snip;

import io.github.lingjiuu.agent.turn.pipeline.PreModelContext;
import io.github.lingjiuu.agent.turn.pipeline.PreModelStep;
import io.github.lingjiuu.agent.turn.pipeline.PreModelStepResult;
import io.github.lingjiuu.message.Message;

import java.util.List;

public class SnipStep implements PreModelStep {

    private final SnipService snipService;

    public SnipStep() {
        this(new SnipService());
    }

    public SnipStep(SnipService snipService) {
        if (snipService == null) {
            throw new IllegalArgumentException("snipService must not be null");
        }
        this.snipService = snipService;
    }

    @Override
    public PreModelStepResult apply(PreModelContext context, List<Message> messages) {
        SnipPlan plan = snipService.snipIfNeeded(messages);
        if (!plan.executed()) {
            return PreModelStepResult.unchanged(messages);
        }
        return new PreModelStepResult(plan.messages(), List.of(plan.boundaryMessage()));
    }
}
