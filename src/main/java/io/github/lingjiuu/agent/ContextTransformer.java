package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;

import java.util.List;

public interface ContextTransformer {

    List<Message> transformContext(List<Message> messages);
}
