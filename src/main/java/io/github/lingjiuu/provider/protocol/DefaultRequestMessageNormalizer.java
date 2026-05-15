package io.github.lingjiuu.provider.protocol;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.AttachmentMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.attachment.Attachment;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

public class DefaultRequestMessageNormalizer implements RequestMessageNormalizer {

    @Override
    public List<NormalizedRequestMessage> normalize(List<Message> messages, List<ToolDefinition> tools) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<NormalizedRequestMessage> normalizedMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            switch (message.role()) {
                case USER -> appendMerged(normalizedMessages, normalizeUserMessage((UserMessage) message));
                case ASSISTANT -> appendAssistantMessage(normalizedMessages, (AssistantMessage) message);
                case TOOLRESULT -> appendMerged(normalizedMessages, normalizeToolResultMessage((ToolResultMessage) message));
                case ATTACHMENT -> appendMerged(normalizedMessages, normalizeAttachmentMessage((AttachmentMessage) message));
                case SYSTEM -> appendMerged(normalizedMessages, normalizeSystemMessage((SystemMessage) message));
            }
        }
        return List.copyOf(normalizedMessages);
    }

    private NormalizedUserMessage normalizeUserMessage(UserMessage userMessage) {
        List<NormalizedContent> contents = normalizeTextAndImages(userMessage.messageContents());
        if (contents.isEmpty()) {
            return null;
        }
        return new NormalizedUserMessage(contents);
    }

    private void appendAssistantMessage(List<NormalizedRequestMessage> output, AssistantMessage assistantMessage) {
        List<NormalizedContent> contents = new ArrayList<>();
        for (MessageContent content : assistantMessage.messageContents()) {
            if (content instanceof TextContent textContent) {
                String text = textContent.getText() == null ? "" : textContent.getText().trim();
                if (!text.isBlank()) {
                    contents.add(new NormalizedTextContent(text));
                }
            } else if (content instanceof ThinkingContent thinkingContent) {
                String thinking = thinkingContent.getThinking() == null ? "" : thinkingContent.getThinking().trim();
                if (!thinking.isBlank()) {
                    contents.add(new NormalizedThinkingContent(thinking));
                }
            } else if (content instanceof ToolCallContent toolCallContent) {
                contents.add(new NormalizedToolCallContent(
                        toolCallContent.getToolCallId(),
                        toolCallContent.getToolName(),
                        resolveArgumentsJson(toolCallContent)
                ));
            }
        }

        if (!contents.isEmpty() || assistantMessage.getProviderState() != null) {
            output.add(new NormalizedAssistantMessage(contents, assistantMessage.getProviderState()));
        }
    }

    private NormalizedContextMessage normalizeToolResultMessage(ToolResultMessage toolResultMessage) {
        return new NormalizedContextMessage(List.of(new NormalizedToolResultContent(
                toolResultMessage.getToolCallId(),
                toolResultMessage.getToolName(),
                MessageContents.text(toolResultMessage),
                imagesOf(toolResultMessage.messageContents()),
                toolResultMessage.isError(),
                toolResultMessage.getDetails()
        )));
    }

    private NormalizedContextMessage normalizeAttachmentMessage(AttachmentMessage attachmentMessage) {
        Attachment attachment = attachmentMessage.getAttachment();
        String text = attachment == null ? MessageContents.text(attachmentMessage) : attachment.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new NormalizedContextMessage(List.of(new NormalizedTextContent(text)));
    }

    private NormalizedContextMessage normalizeSystemMessage(SystemMessage systemMessage) {
        if (systemMessage.getSubtype() != SystemMessage.Subtype.INFORMATIONAL) {
            return null;
        }
        String text = MessageContents.text(systemMessage);
        if (text.isBlank()) {
            return null;
        }
        return new NormalizedContextMessage(List.of(new NormalizedTextContent(text)));
    }

    private void appendMerged(List<NormalizedRequestMessage> output, NormalizedRequestMessage nextMessage) {
        if (nextMessage == null || nextMessage.contents().isEmpty()) {
            return;
        }
        if (output.isEmpty()) {
            output.add(nextMessage);
            return;
        }

        NormalizedRequestMessage previousMessage = output.getLast();
        if (previousMessage.kind() == NormalizedRequestMessage.Kind.USER
                && nextMessage.kind() == NormalizedRequestMessage.Kind.USER
                && previousMessage instanceof NormalizedUserMessage previousUser
                && nextMessage instanceof NormalizedUserMessage nextUser) {
            output.set(output.size() - 1, previousUser.withContents(merged(previousUser.contents(), nextUser.contents())));
            return;
        }
        if (previousMessage.kind() == NormalizedRequestMessage.Kind.CONTEXT
                && nextMessage.kind() == NormalizedRequestMessage.Kind.CONTEXT
                && previousMessage instanceof NormalizedContextMessage previousContext
                && nextMessage instanceof NormalizedContextMessage nextContext) {
            output.set(output.size() - 1, previousContext.withContents(merged(previousContext.contents(), nextContext.contents())));
            return;
        }

        output.add(nextMessage);
    }

    private List<NormalizedContent> merged(List<NormalizedContent> first, List<NormalizedContent> second) {
        List<NormalizedContent> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }

    private List<NormalizedContent> normalizeTextAndImages(List<MessageContent> messageContents) {
        List<NormalizedContent> contents = new ArrayList<>();
        if (messageContents == null) {
            return contents;
        }
        for (MessageContent content : messageContents) {
            if (content instanceof TextContent textContent) {
                String text = textContent.getText() == null ? "" : textContent.getText().trim();
                if (!text.isBlank()) {
                    contents.add(new NormalizedTextContent(text));
                }
            } else if (content instanceof ImageContent imageContent) {
                NormalizedImageContent image = normalizeImage(imageContent);
                if (image != null) {
                    contents.add(image);
                }
            }
        }
        return contents;
    }

    private List<NormalizedImageContent> imagesOf(List<MessageContent> messageContents) {
        List<NormalizedImageContent> images = new ArrayList<>();
        if (messageContents == null) {
            return images;
        }
        for (MessageContent content : messageContents) {
            if (content instanceof ImageContent imageContent) {
                NormalizedImageContent image = normalizeImage(imageContent);
                if (image != null) {
                    images.add(image);
                }
            }
        }
        return images;
    }

    private NormalizedImageContent normalizeImage(ImageContent imageContent) {
        String data = imageContent.getData() == null ? "" : imageContent.getData().trim();
        String mimeType = imageContent.getMimeType() == null ? "" : imageContent.getMimeType().trim();
        if (data.isBlank() || mimeType.isBlank()) {
            return null;
        }
        return new NormalizedImageContent(data, mimeType);
    }

    private String resolveArgumentsJson(ToolCallContent toolCallContent) {
        if (toolCallContent.getArgumentsJson() != null && !toolCallContent.getArgumentsJson().isBlank()) {
            return toolCallContent.getArgumentsJson();
        }
        if (toolCallContent.getArguments() != null) {
            return toolCallContent.getArguments().toString();
        }
        return "{}";
    }
}
