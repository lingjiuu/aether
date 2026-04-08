package io.github.lingjiuu.session;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.model.AgentConfig;
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
public class AgentSessionSnapshot {

    private AgentConfig config;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();
}
