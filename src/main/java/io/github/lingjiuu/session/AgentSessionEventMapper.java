package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentEvent;

public final class AgentSessionEventMapper {

    private AgentSessionEventMapper() {
    }

    public static AgentSessionEvent map(String sessionId, AgentEvent event) {
        if (event == null || event.getType() == null) {
            return null;
        }

        AgentSessionEvent.Type type = switch (event.getType()) {
            case RUN_START -> AgentSessionEvent.Type.RUN_START;
            case TURN_START -> AgentSessionEvent.Type.TURN_START;
            case ASSISTANT_TEXT_DELTA -> AgentSessionEvent.Type.ASSISTANT_TEXT_DELTA;
            case REASONING_DELTA -> AgentSessionEvent.Type.REASONING_DELTA;
            case ASSISTANT_MESSAGE -> AgentSessionEvent.Type.ASSISTANT_MESSAGE;
            case TOOL_CALL -> AgentSessionEvent.Type.TOOL_CALL;
            case TOOL_EXECUTION_START -> AgentSessionEvent.Type.TOOL_EXECUTION_START;
            case TOOL_EXECUTION_UPDATE -> AgentSessionEvent.Type.TOOL_EXECUTION_UPDATE;
            case TOOL_EXECUTION_END -> AgentSessionEvent.Type.TOOL_EXECUTION_END;
            case TOOL_RESULT -> AgentSessionEvent.Type.TOOL_RESULT;
            case FINAL_ANSWER -> AgentSessionEvent.Type.FINAL_ANSWER;
            case RUN_END -> AgentSessionEvent.Type.RUN_END;
        };

        return AgentSessionEvent.builder()
                .type(type)
                .sessionId(sessionId)
                .turn(event.getTurn())
                .delta(event.getDelta())
                .text(event.getText())
                .assistantMessage(event.getAssistantMessage())
                .toolCall(event.getToolCall())
                .toolResult(event.getToolResult())
                .partialToolResult(event.getPartialToolResult())
                .build();
    }
}
