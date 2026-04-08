package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;

import java.util.ArrayList;
import java.util.List;

public class DefaultLlmMessageConverter implements LlmMessageConverter {

    @Override
    public List<Message> convertToLlm(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Message> llmMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message != null) {
                llmMessages.add(message);
            }
        }
        return List.copyOf(llmMessages);
    }
}
