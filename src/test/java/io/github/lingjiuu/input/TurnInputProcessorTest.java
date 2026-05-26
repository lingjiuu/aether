package io.github.lingjiuu.input;

import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.skill.SkillsManager;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class TurnInputProcessorTest extends TestCase {

    public void testLocalImageBecomesUserMessageContent() throws Exception {
        Path cwd = Files.createTempDirectory("aether-input");
        Path imagePath = cwd.resolve("pixel.png");
        Files.write(imagePath, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lYI1LgAAAABJRU5ErkJggg=="
        ));
        TurnInputProcessor processor = new TurnInputProcessor(
                cwd,
                SkillsManager.empty(cwd),
                new ContextBuilder()
        );

        ProcessedTurnInput processed = processor.process(TurnInput.builder()
                .text("describe this")
                .localImage(Path.of("pixel.png"))
                .build());

        assertTrue(processed.contextMessages().isEmpty());
        assertEquals(3, processed.userMessage().messageContents().size());
        assertEquals("describe this", ((TextContent) processed.userMessage().messageContents().get(0)).getText());
        assertEquals(
                "Attached local image: pixel.png",
                ((TextContent) processed.userMessage().messageContents().get(1)).getText()
        );
        assertTrue(processed.userMessage().messageContents().get(2) instanceof ImageContent);
        ImageContent image = (ImageContent) processed.userMessage().messageContents().get(2);
        assertEquals("image/png", image.getMimeType());
        assertFalse(image.getData().isBlank());
    }

    public void testSkillInputBecomesContextMessage() throws Exception {
        Path root = Files.createTempDirectory("aether-input-skills");
        Path cwd = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        Path skillPath = cwd.resolve(".aether/skills/demo/SKILL.md");
        writeSkill(skillPath, "demo", "Demo skill", "Use the demo workflow.");
        TurnInputProcessor processor = new TurnInputProcessor(
                cwd,
                new SkillsManager(cwd, agentDir),
                new ContextBuilder()
        );

        ProcessedTurnInput processed = processor.process(TurnInput.builder()
                .skill("demo", skillPath)
                .build());

        assertEquals("Please use the attached context.", MessageContents.text(processed.userMessage()));
        assertEquals(1, processed.contextMessages().size());
        ContextMessage contextMessage = processed.contextMessages().getFirst();
        assertEquals(ContextMessage.ContextKind.SKILL, contextMessage.getKind());
        String text = MessageContents.text(contextMessage);
        assertTrue(text.contains("<name>demo</name>"));
        assertTrue(text.contains("Use the demo workflow."));
    }

    public void testMentionedSkillBecomesContextMessage() throws Exception {
        Path root = Files.createTempDirectory("aether-input-mentioned-skills");
        Path cwd = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        Path skillPath = cwd.resolve(".aether/skills/demo/SKILL.md");
        writeSkill(skillPath, "demo", "Demo skill", "Use the demo workflow.");
        TurnInputProcessor processor = new TurnInputProcessor(
                cwd,
                new SkillsManager(cwd, agentDir),
                new ContextBuilder()
        );

        ProcessedTurnInput processed = processor.process(TurnInput.ofText("Use $demo please"));

        assertEquals("Use $demo please", MessageContents.text(processed.userMessage()));
        assertEquals(1, processed.contextMessages().size());
        assertTrue(MessageContents.text(processed.contextMessages().getFirst()).contains("<name>demo</name>"));
    }

    private void writeSkill(Path path, String name, String description, String body) throws Exception {
        Files.createDirectories(path.getParent());
        String content = """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
