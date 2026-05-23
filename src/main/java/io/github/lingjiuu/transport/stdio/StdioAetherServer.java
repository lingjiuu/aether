package io.github.lingjiuu.transport.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.event.EventSubscription;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiCommandPayloads;
import io.github.lingjiuu.protocol.UiCommandType;
import io.github.lingjiuu.protocol.UiEventPage;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.session.SessionOptions;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.ui.UiRuntime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StdioAetherServer implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "aether.stdio.v1";

    private static final Logger LOGGER = Logger.getLogger(StdioAetherServer.class.getName());
    private static final long PARSE_ERROR = -32700;
    private static final long INVALID_REQUEST = -32600;
    private static final long METHOD_NOT_FOUND = -32601;
    private static final long INVALID_PARAMS = -32602;
    private static final long INTERNAL_ERROR = -32603;

    private final ObjectMapper objectMapper;
    private final UiRuntime uiRuntime;
    private final BufferedReader reader;
    private final StdioJsonWriter writer;
    private EventSubscription eventSubscription;

    public StdioAetherServer(SessionFactory sessionFactory) {
        this(sessionFactory, SessionOptions.defaults());
    }

    public StdioAetherServer(SessionFactory sessionFactory, SessionOptions defaultSessionOptions) {
        this(
                new UiRuntime(sessionFactory, defaultSessionOptions, null, null),
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
        );
    }

    public StdioAetherServer(UiRuntime uiRuntime, Reader reader, Writer writer) {
        if (uiRuntime == null) {
            throw new IllegalArgumentException("uiRuntime must not be null");
        }
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.uiRuntime = uiRuntime;
        this.reader = new BufferedReader(reader);
        this.writer = new StdioJsonWriter(objectMapper, writer);
    }

    public void run() {
        eventSubscription = uiRuntime.subscribe(event -> writer.writeNotification("event", event));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    handleLine(line);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "stdio transport stopped with an error", e);
            writer.writeError(null, INTERNAL_ERROR, e.getMessage(), null);
        } finally {
            close();
        }
    }

    void handleLine(String line) {
        JsonNode message;
        try {
            message = objectMapper.readTree(line);
        } catch (Exception e) {
            writer.writeError(null, PARSE_ERROR, "Parse error", e.getMessage());
            return;
        }

        JsonNode id = message.get("id");
        boolean expectsResponse = message.has("id");
        JsonNode methodNode = message.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            if (expectsResponse) {
                writer.writeError(id, INVALID_REQUEST, "Request method is required.", null);
            }
            return;
        }

        String method = methodNode.asText();
        JsonNode params = message.get("params");
        try {
            Object result = handle(method, id, params);
            if (expectsResponse) {
                writer.writeResponse(id, result);
            }
        } catch (ProtocolException e) {
            if (expectsResponse) {
                writer.writeError(id, e.code(), e.getMessage(), null);
            }
        } catch (RuntimeException e) {
            if (expectsResponse) {
                writer.writeError(id, INTERNAL_ERROR, e.getMessage(), null);
            }
        }
    }

    @Override
    public void close() {
        if (eventSubscription != null) {
            eventSubscription.close();
            eventSubscription = null;
        }
        uiRuntime.close();
        writer.flush();
    }

    private Object handle(String method, JsonNode id, JsonNode params) {
        return switch (method) {
            case "initialize" -> initializeResult();
            case "session/new" -> submitNewSession(id, params);
            case "session/close" -> submitSimple(id, UiCommandType.CLOSE_SESSION);
            case "session/name/set" -> submitSessionName(id, params);
            case "session/resume" -> submitResume(id, params);
            case "session/list" -> uiRuntime.listSessions();
            case "session/current" -> uiRuntime.currentSessionState();
            case "history/read" -> uiRuntime.history();
            case "events/list" -> eventPage(params);
            case "turn/submit" -> submitUserInput(id, params);
            case "turn/cancel" -> submitSimple(id, UiCommandType.CANCEL_TURN);
            case "turn/continue" -> submitSimple(id, UiCommandType.CONTINUE);
            case "compact/run" -> submitSimple(id, UiCommandType.COMPACT);
            case "skills/list" -> skillsList();
            case "skills/reload" -> submitSimple(id, UiCommandType.RELOAD_SKILLS);
            case "approval/respond" -> submitApprovalResponse(id, params);
            case "initialized" -> Map.of("ok", true);
            default -> throw new ProtocolException(METHOD_NOT_FOUND, "Unknown method: " + method);
        };
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "sessionId", uiRuntime.sessionId(),
                "session", uiRuntime.currentSessionState(),
                "history", uiRuntime.history(),
                "capabilities", Map.of(
                        "events", true,
                        "history", true,
                        "approval", true,
                        "cancelTurn", true,
                        "sessions", true,
                        "sessionName", true,
                        "sessionState", true,
                        "eventPaging", true
                )
        );
    }

    private UiCommandAck submitUserInput(JsonNode id, JsonNode params) {
        String text = textParam(params, "text");
        if (text == null || text.isBlank()) {
            throw new ProtocolException(INVALID_PARAMS, "turn/submit requires params.text.");
        }
        return uiRuntime.submit(UiCommand.builder()
                .commandId(commandId(id))
                .type(UiCommandType.SUBMIT_USER_INPUT)
                .payload(new UiCommandPayloads.SubmitUserInput(text))
                .build());
    }

    private UiCommandAck submitResume(JsonNode id, JsonNode params) {
        String sessionId = textParam(params, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new ProtocolException(INVALID_PARAMS, "session/resume requires params.sessionId.");
        }
        return uiRuntime.submit(UiCommand.builder()
                .commandId(commandId(id))
                .type(UiCommandType.RESUME_SESSION)
                .payload(new UiCommandPayloads.ResumeSession(sessionId))
                .build());
    }

    private UiCommandAck submitSessionName(JsonNode id, JsonNode params) {
        String name = textParam(params, "name");
        if (name == null || name.isBlank()) {
            throw new ProtocolException(INVALID_PARAMS, "session/name/set requires params.name.");
        }
        return uiRuntime.submit(UiCommand.builder()
                .commandId(commandId(id))
                .type(UiCommandType.SET_SESSION_NAME)
                .payload(new UiCommandPayloads.SetSessionName(name))
                .build());
    }

    private UiCommandAck submitNewSession(JsonNode id, JsonNode params) {
        String cwd = textParam(params, "cwd");
        if (cwd == null || cwd.isBlank()) {
            throw new ProtocolException(INVALID_PARAMS, "session/new requires params.cwd.");
        }
        return uiRuntime.submit(UiCommand.builder()
                .commandId(commandId(id))
                .type(UiCommandType.NEW_SESSION)
                .payload(new UiCommandPayloads.NewSession(cwd))
                .build());
    }

    private UiCommandAck submitApprovalResponse(JsonNode id, JsonNode params) {
        String approvalId = textParam(params, "approvalId");
        if (approvalId == null || approvalId.isBlank()) {
            throw new ProtocolException(INVALID_PARAMS, "approval/respond requires params.approvalId.");
        }
        return uiRuntime.submit(UiCommand.builder()
                .commandId(commandId(id))
                .type(UiCommandType.APPROVAL_RESPONSE)
                .payload(new UiCommandPayloads.ApprovalResponse(
                        approvalId,
                        booleanParam(params, "approved"),
                        textParam(params, "reason")
                ))
                .build());
    }

    private UiCommandAck submitSimple(JsonNode id, UiCommandType type) {
        return uiRuntime.submit(UiCommand.builder()
                .commandId(commandId(id))
                .type(type)
                .build());
    }

    private List<SkillInfo> skillsList() {
        return uiRuntime.availableSkills()
                .stream()
                .map(SkillInfo::from)
                .toList();
    }

    private UiEventPage eventPage(JsonNode params) {
        long afterSequence = afterSequence(params);
        String sessionId = textParam(params, "sessionId");
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(uiRuntime.sessionId())) {
            return new UiEventPage(
                    sessionId,
                    afterSequence,
                    List.of(),
                    afterSequence,
                    false,
                    true
            );
        }
        return uiRuntime.eventPage(afterSequence, limit(params));
    }

    private String commandId(JsonNode id) {
        if (id == null || id.isNull()) {
            return UiCommand.newCommandId();
        }
        if (id.isTextual()) {
            return id.asText();
        }
        return id.toString();
    }

    private String textParam(JsonNode params, String name) {
        if (params == null || params.isNull()) {
            return null;
        }
        if (params.isTextual()) {
            return params.asText();
        }
        JsonNode value = params.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private boolean booleanParam(JsonNode params, String name) {
        if (params == null || params.isNull()) {
            return false;
        }
        JsonNode value = params.get(name);
        return value != null && value.asBoolean(false);
    }

    private long afterSequence(JsonNode params) {
        if (params == null || params.isNull()) {
            return 0;
        }
        if (params.isNumber()) {
            return Math.max(0, params.asLong());
        }
        JsonNode value = params.get("afterSequence");
        return value == null ? 0 : Math.max(0, value.asLong(0));
    }

    private int limit(JsonNode params) {
        if (params == null || params.isNull() || params.isNumber()) {
            return 200;
        }
        JsonNode value = params.get("limit");
        int limit = value == null ? 200 : value.asInt(200);
        return Math.max(1, Math.min(limit, 1000));
    }

    private record SkillInfo(
            String name,
            String description,
            String location,
            boolean disableModelInvocation
    ) {
        private static SkillInfo from(Skill skill) {
            return new SkillInfo(
                    skill.getName(),
                    skill.getDescription(),
                    skill.getLocation() == null ? null : skill.getLocation().toString(),
                    skill.isDisableModelInvocation()
            );
        }
    }

    private static final class ProtocolException extends RuntimeException {
        private final long code;

        private ProtocolException(long code, String message) {
            super(message);
            this.code = code;
        }

        private long code() {
            return code;
        }
    }
}
