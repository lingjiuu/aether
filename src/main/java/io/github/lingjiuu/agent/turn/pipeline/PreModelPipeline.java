package io.github.lingjiuu.agent.turn.pipeline;

import io.github.lingjiuu.message.Message;

import java.util.List;

public interface PreModelPipeline {

    PreModelPipelineResult apply(PreModelContext context, List<Message> messages);
}
