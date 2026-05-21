package io.github.lingjiuu.skill;

import io.github.lingjiuu.input.TurnInput;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SkillsManagerTest extends TestCase {

    public void testDiscoversAndInjectsMentionedSkill() throws Exception {
        Path root = Files.createTempDirectory("aether-skills");
        Path cwd = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        Path skillPath = cwd.resolve(".aether/skills/demo/SKILL.md");
        writeSkill(skillPath, "demo", "Demo skill", "Use the demo workflow.");

        SkillsManager manager = new SkillsManager(cwd, agentDir);

        assertEquals(1, manager.availableSkills().size());
        List<SkillInjection> injections = manager.resolveSkillInjections(TurnInput.ofText("Use $demo please"));
        assertEquals(1, injections.size());
        assertEquals("demo", injections.get(0).name());
        assertEquals(skillPath.toAbsolutePath().normalize(), injections.get(0).path());
        assertTrue(injections.get(0).contents().contains("Use the demo workflow."));
    }

    public void testStructuredSkillInputResolvesByPath() throws Exception {
        Path root = Files.createTempDirectory("aether-skills");
        Path cwd = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        Path skillPath = agentDir.resolve("skills/review/SKILL.md");
        writeSkill(skillPath, "review", "Review skill", "Review carefully.");

        SkillsManager manager = new SkillsManager(cwd, agentDir);

        List<SkillInjection> injections = manager.resolveSkillInjections(TurnInput.builder()
                .text("Use the selected skill")
                .skill("review", skillPath)
                .build());
        assertEquals(1, injections.size());
        assertEquals("review", injections.get(0).name());
    }

    public void testDisabledSkillIsNotAvailableOrInjected() throws Exception {
        Path root = Files.createTempDirectory("aether-skills");
        Path cwd = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        writeSkill(
                cwd.resolve(".aether/skills/hidden/SKILL.md"),
                "hidden",
                "Hidden skill",
                "Should not be injected.",
                true
        );

        SkillsManager manager = new SkillsManager(cwd, agentDir);

        assertTrue(manager.availableSkills().isEmpty());
        assertTrue(manager.resolveSkillInjections(TurnInput.ofText("Use $hidden")).isEmpty());
    }

    public void testReloadRefreshesSkillSnapshot() throws Exception {
        Path root = Files.createTempDirectory("aether-skills");
        Path cwd = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        SkillsManager manager = new SkillsManager(cwd, agentDir);

        assertTrue(manager.availableSkills().isEmpty());

        Path skillPath = cwd.resolve(".aether/skills/demo/SKILL.md");
        writeSkill(skillPath, "demo", "Demo skill", "Use the demo workflow.");

        assertEquals(1, manager.reload());
        assertEquals(1, manager.availableSkills().size());

        Files.delete(skillPath);

        assertEquals(0, manager.reload());
        assertTrue(manager.availableSkills().isEmpty());
    }

    private void writeSkill(Path path, String name, String description, String body) throws Exception {
        writeSkill(path, name, description, body, false);
    }

    private void writeSkill(
            Path path,
            String name,
            String description,
            String body,
            boolean disabled
    ) throws Exception {
        Files.createDirectories(path.getParent());
        String content = """
                ---
                name: %s
                description: %s
                disable-model-invocation: %s
                ---
                %s
                """.formatted(name, description, disabled, body);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
