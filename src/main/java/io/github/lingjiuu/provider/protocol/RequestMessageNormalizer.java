package io.github.lingjiuu.provider.protocol;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.List;

public interface RequestMessageNormalizer {

    List<NormalizedRequestMessage> normalize(List<Message> messages, List<ToolDefinition> tools);
}
