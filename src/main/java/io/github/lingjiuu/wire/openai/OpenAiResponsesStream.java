package io.github.lingjiuu.wire.openai;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.AssistantStreamEvent;

import java.io.IOException;
import java.util.function.Consumer;

final class OpenAiResponsesStream extends AssistantStream {

    private final StreamResponse<ResponseStreamEvent> streamResponse;
    private final OpenAiStreamEventMapper eventMapper;
    private AssistantMessage result;

    OpenAiResponsesStream(StreamResponse<ResponseStreamEvent> streamResponse, String model, String provider) {
        this.streamResponse = streamResponse;
        this.eventMapper = new OpenAiStreamEventMapper(model, provider);
    }

    @Override
    public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
        try {
            for (ResponseStreamEvent event : (Iterable<ResponseStreamEvent>) streamResponse.stream()::iterator) {
                for (AssistantStreamEvent assistantEvent : eventMapper.mapAll(event)) {
                    AssistantMessage terminalMessage = consumeEvent(consumer, assistantEvent);
                    if (terminalMessage != null) {
                        result = terminalMessage;
                        return result;
                    }
                }
            }

            for (AssistantStreamEvent assistantEvent : eventMapper.drainPending()) {
                AssistantMessage terminalMessage = consumeEvent(consumer, assistantEvent);
                if (terminalMessage != null) {
                    result = terminalMessage;
                    return result;
                }
            }

            AssistantStreamEvent errorEvent = eventMapper.unexpectedEnd();
            consumer.accept(errorEvent);
            result = errorEvent.getError();
            return result;
        } catch (Throwable e) {
            if (isFatalThrowable(e)) {
                throw e;
            }
            AssistantStreamEvent errorEvent = eventMapper.error(e);
            consumer.accept(errorEvent);
            result = errorEvent.getError();
            return result;
        }
    }

    @Override
    public AssistantMessage result() {
        return result;
    }

    @Override
    public void close() throws IOException {
        streamResponse.close();
    }

    private AssistantMessage terminalMessage(AssistantStreamEvent event) {
        if (event.getType() == AssistantStreamEvent.Type.DONE) {
            return event.getMessage();
        }
        if (event.getType() == AssistantStreamEvent.Type.ERROR) {
            return event.getError();
        }
        return null;
    }

    private AssistantMessage consumeEvent(Consumer<AssistantStreamEvent> consumer, AssistantStreamEvent assistantEvent) {
        if (assistantEvent == null) {
            return null;
        }
        consumer.accept(assistantEvent);
        return terminalMessage(assistantEvent);
    }

    private static boolean isFatalThrowable(Throwable throwable) {
        return throwable instanceof VirtualMachineError
                || (throwable != null && "java.lang.ThreadDeath".equals(throwable.getClass().getName()));
    }
}
