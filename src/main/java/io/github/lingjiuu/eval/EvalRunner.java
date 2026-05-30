package io.github.lingjiuu.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.lingjiuu.infra.logging.AetherLogging;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiCommandType;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiHistory;
import io.github.lingjiuu.protocol.UiSessionState;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.session.SessionOptions;
import io.github.lingjiuu.ui.UiRuntime;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class EvalRunner {

    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final int DEFAULT_POLL_MS = 1000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern TOML_API_KEY = Pattern.compile(
            "^(\\s*api_key\\s*=\\s*)(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')",
            Pattern.MULTILINE
    );

    private EvalRunner() {
    }

    public static void main(String[] args) {
        int exitCode = new EvalRunner().run(args);
        System.exit(exitCode);
    }

    private int run(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv);
            if (args.help()) {
                printHelp();
                return 0;
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            printHelp();
            return 2;
        }

        long startedAt = System.currentTimeMillis();
        Map<String, Object> modelConfig = Map.of();
        SummaryContext summaryContext = null;

        try {
            String instruction = readInstruction(args);
            Path evalConfigPath = evalConfigPath(args);
            EvalConfig evalConfig = loadEvalConfig(evalConfigPath);
            modelConfig = modelConfigSummary(evalConfig);
            Path sessionCwd = pathArg(
                    args.sessionCwd(),
                    env("AETHER_SESSION_CWD"),
                    Path.of(System.getProperty("user.dir")).toString()
            );
            Path artifactDir = artifactDir(args);
            int timeoutSeconds = positiveIntArg(
                    args.timeoutSeconds(),
                    env("AETHER_EVAL_TIMEOUT_SECONDS"),
                    DEFAULT_TIMEOUT_SECONDS
            );
            int pollMs = positiveIntArg(
                    args.pollMs(),
                    env("AETHER_EVAL_POLL_MS"),
                    DEFAULT_POLL_MS
            );
            String permissionMode = stringArg(
                    args.permissionMode(),
                    env("AETHER_EVAL_PERMISSION_MODE"),
                    "FULL_ACCESS"
            );
            Path home = pathArg(
                    args.home(),
                    env("AETHER_EVAL_HOME"),
                    env("HOME"),
                    System.getProperty("user.home")
            );
            Path summaryFile = args.summaryFile() == null ? null : pathArg(args.summaryFile());
            summaryContext = new SummaryContext(
                    modelConfig,
                    evalConfigPath,
                    sessionCwd,
                    artifactDir,
                    home,
                    timeoutSeconds,
                    pollMs,
                    permissionMode,
                    summaryFile
            );

            if (args.dryRun()) {
                Map<String, Object> dryRun = baseSummary(summaryContext);
                dryRun.put("instructionLength", instruction.length());
                dryRun.put("dryRun", true);
                System.out.println(toJson(dryRun));
                return 0;
            }

            Files.createDirectories(home);
            System.setProperty("user.home", home.toString());
            writeAetherConfig(home, evalConfig);

            AetherLogging.initialize();

            List<UiEvent> events = new CopyOnWriteArrayList<>();
            AtomicLong lastSequence = new AtomicLong();
            UiSessionState sessionState;
            UiHistory history;
            boolean timedOut = false;
            boolean cancelled = false;

            try (UiRuntime runtime = new UiRuntime(
                    SessionFactory.createDefault(),
                    SessionOptions.cwd(sessionCwd),
                    event -> {
                        events.add(event);
                        if (event != null && event.getSequence() != null) {
                            lastSequence.updateAndGet(current -> Math.max(current, event.getSequence()));
                        }
                    },
                    null
            )) {
                ensureAccepted(
                        runtime.submit(UiCommand.setPermissionMode(permissionMode)),
                        "permission/set"
                );
                ensureAccepted(
                        runtime.submit(UiCommand.submitUserInput(instruction)),
                        "turn/submit"
                );

                boolean idle = waitForIdle(runtime, Duration.ofSeconds(timeoutSeconds), pollMs);
                if (!idle) {
                    timedOut = true;
                    UiCommandAck cancelAck = runtime.submit(UiCommand.simple(UiCommandType.CANCEL_TURN));
                    cancelled = cancelAck != null && cancelAck.accepted();
                    idle = waitForIdle(runtime, Duration.ofSeconds(10), Math.min(pollMs, 1000));
                }

                if (idle) {
                    runtime.waitForIdle();
                }
                sessionState = runtime.currentSessionState();
                history = runtime.history();
            }

            copyAetherArtifacts(home, artifactDir);

            long eventErrors = countEventType(events, UiEventType.ERROR);
            long turnAborted = countEventType(events, UiEventType.TURN_ABORTED);
            Map<String, Object> summary = baseSummary(summaryContext);
            summary.put("ok", !timedOut && eventErrors == 0 && turnAborted == 0);
            summary.put("timedOut", timedOut);
            summary.put("cancelled", cancelled);
            summary.put("protocolVersion", "aether.eval.java.v1");
            summary.put("runner", Map.of(
                    "kind", "java-eval-runner",
                    "className", EvalRunner.class.getName()
            ));
            summary.put("sessionId", sessionState == null ? null : sessionState.sessionId());
            summary.put("status", sessionState == null ? null : sessionState.status());
            summary.put("messageCount", sessionState == null ? null : sessionState.messageCount());
            summary.put("permissionMode", sessionState == null ? permissionMode : sessionState.permissionMode());
            summary.put("tokenUsage", sessionState == null ? null : sessionState.tokenUsage());
            summary.put("durationMs", System.currentTimeMillis() - startedAt);
            summary.put("eventCount", events.size());
            summary.put("lastSequence", lastSequence.get());
            summary.put("eventTypes", eventTypeCounts(events));
            summary.put("eventErrorCount", eventErrors);
            summary.put("turnStarted", countEventType(events, UiEventType.TURN_STARTED) > 0);
            summary.put("turnCompleted", countEventType(events, UiEventType.TURN_COMPLETED) > 0);
            summary.put("turnAborted", turnAborted > 0);
            summary.put("historyTurns", history == null || history.turns() == null ? null : history.turns().size());

            writeSummary(summary, summaryFile, artifactDir);
            System.out.println(toJson(summary));
            return timedOut ? 124 : 0;
        } catch (Throwable e) {
            Map<String, Object> summary = new LinkedHashMap<>();
            if (summaryContext != null) {
                summary.putAll(baseSummary(summaryContext));
            } else {
                summary.put("modelConfig", modelConfig);
            }
            summary.put("ok", false);
            summary.put("error", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            summary.put("errorType", e.getClass().getName());
            summary.put("durationMs", System.currentTimeMillis() - startedAt);
            try {
                if (summaryContext != null) {
                    writeSummary(summary, summaryContext.summaryFile(), summaryContext.artifactDir());
                }
            } catch (RuntimeException ignored) {
                // The original error is more useful to the harness.
            }
            System.err.println(toJson(summary));
            return 1;
        }
    }

    private static boolean waitForIdle(UiRuntime runtime, Duration timeout, int pollMs) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            UiSessionState state = runtime.currentSessionState();
            if (state == null || !"RUNNING".equalsIgnoreCase(state.status())) {
                runtime.waitForIdle();
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.max(1, pollMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Aether to become idle.", e);
            }
        }
    }

    private static void ensureAccepted(UiCommandAck ack, String label) {
        if (ack == null || !ack.accepted()) {
            String message = ack == null ? "no acknowledgement" : ack.message();
            throw new IllegalStateException(label + " rejected: " + message);
        }
    }

    private static String readInstruction(Args args) throws IOException {
        if (args.instructionFile() != null) {
            return Files.readString(pathArg(args.instructionFile()), StandardCharsets.UTF_8).trim();
        }
        if (args.instruction() != null) {
            return args.instruction().trim();
        }
        if (!args.positionals().isEmpty()) {
            return String.join(" ", args.positionals()).trim();
        }
        throw new IllegalArgumentException("instruction is required; pass --instruction or --instruction-file");
    }

    private static Path artifactDir(Args args) {
        if (args.artifactDir() != null) {
            return pathArg(args.artifactDir());
        }
        String envValue = env("AETHER_EVAL_ARTIFACT_DIR");
        if (envValue != null) {
            return pathArg(envValue);
        }
        Path harborArtifacts = Path.of("/logs/artifacts");
        return Files.isDirectory(harborArtifacts) ? harborArtifacts : null;
    }

    private static Path evalConfigPath(Args args) {
        if (args.evalConfig() != null) {
            return pathArg(args.evalConfig());
        }
        String envValue = env("AETHER_EVAL_CONFIG");
        if (envValue != null) {
            return pathArg(envValue);
        }
        Path localConfig = Path.of(System.getProperty("user.dir"), "evals", "aether-eval.toml")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(localConfig)) {
            return localConfig;
        }
        throw new IllegalArgumentException(
                "Eval config is required. Pass --eval-config or set AETHER_EVAL_CONFIG."
        );
    }

    private static EvalConfig loadEvalConfig(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Eval config not found: " + path);
        }
        TomlParseResult parsed = Toml.parse(path);
        if (parsed.hasErrors()) {
            throw new IllegalArgumentException("Failed to parse eval config " + path + ": " + formatTomlErrors(parsed.errors()));
        }
        String baseUrl = firstNonBlank(parsed.getString("base_url"), parsed.getString("baseurl"));
        String model = firstNonBlank(parsed.getString("model"), parsed.getString("model_id"));
        String thinkingLevel = firstNonBlank(parsed.getString("thinking_level"), parsed.getString("thinking-level"));
        String apiKey = firstNonBlank(parsed.getString("api_key"), parsed.getString("api-key"));
        Long autoCompactTokenLimit = parsed.getLong("auto_compact_token_limit");
        if (baseUrl == null) {
            throw new IllegalArgumentException("Eval config must define base_url.");
        }
        if (model == null) {
            throw new IllegalArgumentException("Eval config must define model.");
        }
        if (thinkingLevel == null) {
            throw new IllegalArgumentException("Eval config must define thinking_level.");
        }
        validateThinkingLevel(thinkingLevel);
        if (apiKey == null) {
            throw new IllegalArgumentException("Eval config must define api_key.");
        }
        if (autoCompactTokenLimit == null || autoCompactTokenLimit <= 0) {
            throw new IllegalArgumentException("Eval config must define positive auto_compact_token_limit.");
        }
        return new EvalConfig(path, baseUrl, model, thinkingLevel, autoCompactTokenLimit, apiKey);
    }

    private static void writeSummary(Map<String, Object> summary, Path summaryFile, Path artifactDir) {
        String body = toJson(summary) + System.lineSeparator();
        try {
            if (summaryFile != null) {
                Files.createDirectories(summaryFile.toAbsolutePath().normalize().getParent());
                Files.writeString(summaryFile, body, StandardCharsets.UTF_8);
            }
            if (artifactDir != null) {
                Files.createDirectories(artifactDir);
                Files.writeString(
                        artifactDir.resolve("aether-eval-summary.json"),
                        body,
                        StandardCharsets.UTF_8
                );
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write eval summary.", e);
        }
    }

    private static void copyAetherArtifacts(Path home, Path artifactDir) {
        if (artifactDir == null) {
            return;
        }
        Path aetherDir = home.resolve(".aether");
        if (!Files.isDirectory(aetherDir)) {
            return;
        }
        Path outputDir = artifactDir.resolve("aether-home");
        try {
            Files.createDirectories(artifactDir);
            deleteIfExists(outputDir);
            copyDirectory(aetherDir, outputDir);
            redactCopiedConfig(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy Aether artifacts.", e);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void redactCopiedConfig(Path outputDir) throws IOException {
        Path copiedConfig = outputDir.resolve("config.toml");
        if (!Files.exists(copiedConfig)) {
            return;
        }
        String original = Files.readString(copiedConfig, StandardCharsets.UTF_8);
        String redacted = TOML_API_KEY.matcher(original).replaceAll("$1\"<redacted>\"");
        Files.writeString(copiedConfig, redacted, StandardCharsets.UTF_8);
    }

    private static void writeAetherConfig(Path home, EvalConfig evalConfig) throws IOException {
        String envName = envReference(evalConfig.apiKey());
        if (envName != null && env(envName) == null) {
            throw new IllegalStateException(envName + " is required because eval config api_key references $" + envName + ".");
        }
        Path configPath = home.resolve(".aether").resolve("config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, aetherConfigToml(evalConfig), StandardCharsets.UTF_8);
    }

    private static String aetherConfigToml(EvalConfig evalConfig) {
        List<String> lines = new ArrayList<>();
        lines.add("default_provider = \"eval\"");
        lines.add("default_model = " + tomlString(evalConfig.model()));
        lines.add("default_thinking_level = " + tomlString(evalConfig.thinkingLevel()));
        lines.add("");
        lines.add("[model_providers.eval]");
        lines.add("name = \"eval\"");
        lines.add("api = \"openai\"");
        lines.add("base_url = " + tomlString(evalConfig.baseUrl()));
        lines.add("api_key = " + tomlString(evalConfig.apiKey()));
        lines.add("");
        lines.add("[[model_providers.eval.models]]");
        lines.add("id = " + tomlString(evalConfig.model()));
        lines.add("name = " + tomlString(evalConfig.model()));
        lines.add("api = \"openai\"");
        lines.add("base_url = " + tomlString(evalConfig.baseUrl()));
        lines.add("input = [\"text\"]");
        lines.add("auto_compact_token_limit = " + evalConfig.autoCompactTokenLimit());
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static Map<String, Object> modelConfigSummary(EvalConfig evalConfig) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "eval-config");
        summary.put("configPath", evalConfig.path().toString());
        summary.put("providerId", "eval");
        summary.put("modelId", evalConfig.model());
        summary.put("baseUrl", evalConfig.baseUrl());
        summary.put("thinkingLevel", evalConfig.thinkingLevel());
        summary.put("autoCompactTokenLimit", evalConfig.autoCompactTokenLimit());
        String envName = envReference(evalConfig.apiKey());
        summary.put("apiKeySource", envName == null ? "inline" : "env:" + envName);
        summary.put("apiKeyEnvPresent", envName == null ? null : env(envName) != null);
        return summary;
    }

    private static Map<String, Object> baseSummary(SummaryContext context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("modelConfig", context.modelConfig());
        summary.put("evalConfigPath", context.evalConfigPath().toString());
        summary.put("sessionCwd", context.sessionCwd().toString());
        summary.put("artifactDir", context.artifactDir() == null ? null : context.artifactDir().toString());
        summary.put("aetherHome", context.home().toString());
        summary.put("timeoutSeconds", context.timeoutSeconds());
        summary.put("pollMs", context.pollMs());
        summary.put("requestedPermissionMode", context.permissionMode());
        return summary;
    }

    private static long countEventType(List<UiEvent> events, UiEventType type) {
        return events.stream()
                .filter(Objects::nonNull)
                .filter(event -> event.getType() == type)
                .count();
    }

    private static Map<String, Long> eventTypeCounts(List<UiEvent> events) {
        Map<UiEventType, Long> counts = new EnumMap<>(UiEventType.class);
        for (UiEvent event : events) {
            if (event != null && event.getType() != null) {
                counts.merge(event.getType(), 1L, Long::sum);
            }
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<UiEventType, Long> entry : counts.entrySet()) {
            result.put(entry.getKey().name(), entry.getValue());
        }
        return result;
    }

    private static int positiveIntArg(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            try {
                int number = Integer.parseInt(value.toString().trim());
                if (number > 0) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // Try the next source.
            }
        }
        throw new IllegalArgumentException("No positive integer value provided.");
    }

    private static String stringArg(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Path pathArg(String... values) {
        String value = stringArg(values);
        if (value == null) {
            throw new IllegalArgumentException("path value is required");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void validateThinkingLevel(String value) {
        String normalized = value.trim().replace('-', '_').toUpperCase();
        switch (normalized) {
            case "XHIGH", "HIGH", "MEDIUM", "LOW", "MINIMAL", "NONE" -> {
            }
            default -> throw new IllegalArgumentException(
                    "thinking_level must be one of xhigh, high, medium, low, minimal, none."
            );
        }
    }

    private static String envReference(String value) {
        if (value == null || !value.startsWith("$") || value.length() < 2) {
            return null;
        }
        String name = value.substring(1);
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? name : null;
    }

    private static String formatTomlErrors(List<TomlParseError> errors) {
        List<String> messages = new ArrayList<>();
        for (TomlParseError error : errors) {
            messages.add(error.toString());
        }
        return String.join("; ", messages);
    }

    private static String tomlString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(String.valueOf(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void printHelp() {
        System.out.println("""
                Usage:
                  aether-eval --instruction "..." [options]
                  aether-eval --instruction-file /path/instruction.md [options]

                Options:
                  --session-cwd PATH       Task workspace. Defaults to AETHER_SESSION_CWD or cwd.
                  --permission-mode MODE   Defaults to FULL_ACCESS for sandboxed evals.
                  --eval-config PATH       Eval model config. Defaults to AETHER_EVAL_CONFIG or evals/aether-eval.toml.
                  --timeout-seconds N      Defaults to 900.
                  --poll-ms N              Defaults to 1000.
                  --summary-file PATH      Write summary JSON.
                  --artifact-dir PATH      Copy summary and ~/.aether into this directory.
                  --home PATH              HOME for Aether state. Defaults to AETHER_EVAL_HOME or HOME.
                  --dry-run                Print resolved config without launching Aether.

                Environment:
                  AETHER_SESSION_CWD                Task workspace.
                  AETHER_EVAL_HOME                  Isolated HOME for logs/traces/transcripts.
                  AETHER_EVAL_CONFIG                Path to evals/aether-eval.toml.
                  OPENAI_API_KEY                    API key referenced by the generated config.toml.
                """);
    }

    private record SummaryContext(
            Map<String, Object> modelConfig,
            Path evalConfigPath,
            Path sessionCwd,
            Path artifactDir,
            Path home,
            int timeoutSeconds,
            int pollMs,
            String permissionMode,
            Path summaryFile
    ) {
    }

    private record EvalConfig(
            Path path,
            String baseUrl,
            String model,
            String thinkingLevel,
            long autoCompactTokenLimit,
            String apiKey
    ) {
    }

    private record Args(
            boolean help,
            boolean dryRun,
            String instruction,
            String instructionFile,
            String evalConfig,
            String sessionCwd,
            String permissionMode,
            String timeoutSeconds,
            String pollMs,
            String summaryFile,
            String artifactDir,
            String home,
            List<String> positionals
    ) {

        static Args parse(String[] argv) {
            Map<String, String> options = new LinkedHashMap<>();
            List<String> positionals = new ArrayList<>();
            boolean help = false;
            boolean dryRun = false;
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--dry-run".equals(arg)) {
                    dryRun = true;
                } else if (arg.startsWith("--") && arg.contains("=")) {
                    int separator = arg.indexOf('=');
                    options.put(arg.substring(2, separator), arg.substring(separator + 1));
                } else if (arg.startsWith("--")) {
                    String name = arg.substring(2);
                    String next = i + 1 < argv.length ? argv[i + 1] : null;
                    if (next == null || next.startsWith("--")) {
                        options.put(name, "true");
                    } else {
                        options.put(name, next);
                        i++;
                    }
                } else {
                    positionals.add(arg);
                }
            }
            return new Args(
                    help,
                    dryRun,
                    options.get("instruction"),
                    options.get("instruction-file"),
                    options.get("eval-config"),
                    options.get("session-cwd"),
                    options.get("permission-mode"),
                    options.get("timeout-seconds"),
                    options.get("poll-ms"),
                    options.get("summary-file"),
                    options.get("artifact-dir"),
                    options.get("home"),
                    List.copyOf(positionals)
            );
        }
    }
}
