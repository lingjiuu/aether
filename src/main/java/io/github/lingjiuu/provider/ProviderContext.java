package io.github.lingjiuu.provider;

import io.github.lingjiuu.message.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderContext {

    private String systemPrompt;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Builder.Default
    private List<ProviderTool> tools = new ArrayList<>();
}
