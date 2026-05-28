package io.github.lingjiuu.infra.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.util.FileSize;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import io.github.lingjiuu.infra.config.AetherPaths;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AetherLogging {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private AetherLogging() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        Path logFile = AetherPaths.getLogFilePath();
        try {
            Files.createDirectories(logFile.getParent());
            setOwnerOnly(logFile.getParent(), true);
        } catch (Exception ignored) {
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} - %msg%n");
        encoder.start();

        ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> console = new ConsoleAppender<>();
        console.setContext(context);
        console.setName("STDERR");
        console.setTarget("System.err");
        console.setEncoder(encoder);
        console.start();

        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file = new RollingFileAppender<>();
        file.setContext(context);
        file.setName("FILE");
        file.setFile(logFile.toString());
        file.setAppend(true);

        PatternLayoutEncoder fileEncoder = new PatternLayoutEncoder();
        fileEncoder.setContext(context);
        fileEncoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{48} - %msg%n");
        fileEncoder.start();
        file.setEncoder(fileEncoder);

        SizeAndTimeBasedRollingPolicy<ch.qos.logback.classic.spi.ILoggingEvent> policy =
                new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(file);
        policy.setFileNamePattern(logFile.getParent().resolve("aether.%d{yyyy-MM-dd}.%i.log").toString());
        policy.setMaxFileSize(FileSize.valueOf("10MB"));
        policy.setMaxHistory(7);
        policy.start();
        file.setRollingPolicy(policy);
        file.start();
        setOwnerOnly(logFile, false);

        ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(console);
        root.addAppender(file);

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static void setOwnerOnly(Path path, boolean directory) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            Set<PosixFilePermission> permissions = directory
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
        } catch (Exception ignored) {
        }
    }
}
