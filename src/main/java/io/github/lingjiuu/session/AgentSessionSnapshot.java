package io.github.lingjiuu.session;

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
public class AgentSessionSnapshot {

    private AgentSessionConfig config;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();
}
