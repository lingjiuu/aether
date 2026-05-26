package io.github.lingjiuu.input;

import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.skill.SkillInjection;
import io.github.lingjiuu.skill.SkillsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class TurnInputProcessor {

    private final Path cwd;
    private final SkillsManager skillsManager;
    private final ContextBuilder contextBuilder;

    public TurnInputProcessor(Path cwd, SkillsManager skillsManager, ContextBuilder contextBuilder) {
        this.cwd = cwd == null ? Path.of(System.getProperty("user.dir")) : cwd.toAbsolutePath().normalize();
        this.skillsManager = skillsManager == null ? SkillsManager.empty(this.cwd) : skillsManager;
        if (contextBuilder == null) {
            throw new IllegalArgumentException("context builder must not be null");
        }
        this.contextBuilder = contextBuilder;
    }

    public ProcessedTurnInput process(TurnInput input) {
        if (input == null) {
            throw new IllegalArgumentException("turn input must not be null");
        }

        List<MessageContent> userContents = new ArrayList<>();
        for (InputItem item : input.items()) {
            if (item instanceof TextInput textInput) {
                userContents.add(TextContent.builder()
                        .text(textInput.text())
                        .build());
            } else if (item instanceof LocalImageInput imageInput) {
                userContents.addAll(processLocalImage(imageInput.path()));
            }
        }

        if (userContents.isEmpty()) {
            userContents.add(TextContent.builder()
                    .text("Please use the attached context.")
                    .build());
        }

        return new ProcessedTurnInput(
                contextBuilder.userMessage(userContents),
                skillContextMessages(input)
        );
    }

    private List<MessageContent> processLocalImage(Path path) {
        Path resolved = resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new InputException("Input image does not exist: " + path);
        }
        try {
            String mimeType = detectImageMimeType(resolved);
            if (mimeType == null) {
                throw new InputException("Unsupported image type: " + path);
            }
            String data = Base64.getEncoder().encodeToString(Files.readAllBytes(resolved));
            return List.of(
                    TextContent.builder()
                            .text("Attached local image: " + displayPath(path))
                            .build(),
                    ImageContent.builder()
                            .mimeType(mimeType)
                            .data(data)
                            .build()
            );
        } catch (IOException e) {
            throw new InputException("Failed to read input image: " + path, e);
        }
    }

    private List<ContextMessage> skillContextMessages(TurnInput input) {
        List<ContextMessage> messages = new ArrayList<>();
        for (SkillInjection injection : skillsManager.resolveSkillInjections(input)) {
            messages.add(contextBuilder.skillContextMessage(injection));
        }
        return List.copyOf(messages);
    }

    private Path resolve(Path path) {
        Path resolved = path.isAbsolute() ? path : cwd.resolve(path);
        return resolved.toAbsolutePath().normalize();
    }

    private String displayPath(Path path) {
        return path == null ? "<unknown>" : path.toString();
    }

    private String detectImageMimeType(Path path) throws IOException {
        byte[] buffer = Files.readAllBytes(path);
        if (startsWith(buffer, new int[]{0xff, 0xd8, 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(buffer, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (startsWithAscii(buffer, 0, "GIF")) {
            return "image/gif";
        }
        if (startsWithAscii(buffer, 0, "RIFF") && startsWithAscii(buffer, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private boolean startsWith(byte[] buffer, int[] bytes) {
        if (buffer == null || buffer.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if ((buffer[i] & 0xff) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithAscii(byte[] buffer, int offset, String text) {
        if (buffer == null || buffer.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((buffer[offset + i] & 0xff) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
