package io.github.lingjiuu.attachment;

import io.github.lingjiuu.agent.turn.pipeline.PreModelContext;
import io.github.lingjiuu.agent.turn.pipeline.PreModelStep;
import io.github.lingjiuu.agent.turn.pipeline.PreModelStepResult;
import io.github.lingjiuu.message.Message;

import java.util.List;

public class AttachmentProjectionStep implements PreModelStep {

    @Override
    public PreModelStepResult apply(PreModelContext context, List<Message> messages) {
        return PreModelStepResult.unchanged(messages);
    }
}
