package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;

import java.util.List;

public interface LlmMessageConverter {

    List<Message> convertToLlm(List<Message> messages);
}
