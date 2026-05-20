package io.github.lingjiuu.input;

import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class InputMaterializer {

    private static final int MAX_FILE_CHARS = 24_000;

    private final Path cwd;

    public InputMaterializer(Path cwd) {
        this.cwd = cwd == null ? Path.of(System.getProperty("user.dir")) : cwd.toAbsolutePath().normalize();
    }

    public MaterializedInput materialize(TurnInput input) {
        if (input == null) {
            throw new IllegalArgumentException("turn input must not be null");
        }

        List<MessageContent> userContents = new ArrayList<>();
        List<ContextMessage> contextMessages = new ArrayList<>();
        for (InputItem item : input.items()) {
            if (item instanceof TextInput textInput) {
                userContents.add(TextContent.builder()
                        .text(textInput.text())
                        .build());
            } else if (item instanceof FileInput fileInput) {
                contextMessages.add(materializeFile(fileInput.path()));
            } else if (item instanceof LocalImageInput imageInput) {
                contextMessages.add(materializeImage(imageInput.path()));
            }
        }

        if (userContents.isEmpty()) {
            userContents.add(TextContent.builder()
                    .text("Please use the attached context.")
                    .build());
        }

        return new MaterializedInput(
                UserMessage.builder()
                        .contents(userContents)
                        .build(),
                contextMessages
        );
    }

    private ContextMessage materializeFile(Path path) {
        Path resolved = resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new InputException("Input file does not exist: " + path);
        }
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            String rendered = renderFileContext(path, content);
            return ContextMessage.builder()
                    .kind(ContextMessage.ContextKind.RESOURCE)
                    .contents(List.of(TextContent.builder()
                            .text(rendered)
                            .build()))
                    .build();
        } catch (IOException e) {
            throw new InputException("Failed to read input file: " + path, e);
        }
    }

    private ContextMessage materializeImage(Path path) {
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
            return ContextMessage.builder()
                    .kind(ContextMessage.ContextKind.RESOURCE)
                    .contents(List.of(
                            TextContent.builder()
                                    .text("Attached local image: " + displayPath(path))
                                    .build(),
                            ImageContent.builder()
                                    .mimeType(mimeType)
                                    .data(data)
                                    .build()
                    ))
                    .build();
        } catch (IOException e) {
            throw new InputException("Failed to read input image: " + path, e);
        }
    }

    private String renderFileContext(Path path, String content) {
        String safeContent = content == null ? "" : content;
        boolean truncated = safeContent.length() > MAX_FILE_CHARS;
        String visibleContent = truncated ? safeContent.substring(0, MAX_FILE_CHARS) : safeContent;
        StringBuilder rendered = new StringBuilder();
        rendered.append("Attached file: ").append(displayPath(path)).append("\n\n");
        rendered.append(visibleContent);
        if (truncated) {
            rendered.append("\n\n[File content truncated after ")
                    .append(MAX_FILE_CHARS)
                    .append(" characters.]");
        }
        return rendered.toString();
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
