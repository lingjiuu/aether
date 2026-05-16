package io.github.lingjiuu.resource;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ResourceLoaderTest extends TestCase {

    public void testLoadPromptResourcesFromProjectAndAgentDirs() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-resource-test");
        Path agentDir = tempDir.resolve("agent");
        Path workspace = tempDir.resolve("workspace");
        Path child = workspace.resolve("app");
        Files.createDirectories(agentDir);
        Files.createDirectories(child.resolve(".aether"));

        Files.writeString(agentDir.resolve("SYSTEM.md"), "Global system");
        Files.writeString(child.resolve(".aether").resolve("SYSTEM.md"), "Project system");
        Files.writeString(agentDir.resolve("APPEND_SYSTEM.md"), "Global append");
        Files.writeString(child.resolve(".aether").resolve("APPEND_SYSTEM.md"), "Project append");
        Files.writeString(agentDir.resolve("AGENTS.md"), "Global agents");
        Files.writeString(workspace.resolve("AGENTS.md"), "Workspace agents");
        Files.writeString(child.resolve("CLAUDE.md"), "Child claude");

        Path visibleSkill = child.resolve(".aether").resolve("skills").resolve("java-test").resolve("SKILL.md");
        Files.createDirectories(visibleSkill.getParent());
        Files.writeString(visibleSkill, """
                ---
                name: java-test
                description: Run Java tests
                ---

                Body is not included in the prompt index.
                """);

        Path hiddenSkill = child.resolve(".aether").resolve("skills").resolve("hidden").resolve("SKILL.md");
        Files.createDirectories(hiddenSkill.getParent());
        Files.writeString(hiddenSkill, """
                ---
                description: Hidden skill
                disable-model-invocation: true
                ---
                """);

        Path invalidSkill = child.resolve(".aether").resolve("skills").resolve("invalid").resolve("SKILL.md");
        Files.createDirectories(invalidSkill.getParent());
        Files.writeString(invalidSkill, """
                ---
                name: invalid
                ---
                """);

        PromptResources resources = new ResourceLoader(child, agentDir).load();

        assertEquals("Project system", resources.getSystemPrompt());
        assertEquals("Project append", resources.getAppendSystemPrompt());
        assertEquals(List.of(
                agentDir.resolve("AGENTS.md").toAbsolutePath().normalize(),
                workspace.resolve("AGENTS.md").toAbsolutePath().normalize(),
                child.resolve("CLAUDE.md").toAbsolutePath().normalize()
        ), resources.getContextFiles().stream().map(ContextFile::getPath).toList());
        assertEquals(List.of("Global agents", "Workspace agents", "Child claude"),
                resources.getContextFiles().stream().map(ContextFile::getContent).toList());

        assertEquals(2, resources.getSkills().size());
        assertEquals("hidden", resources.getSkills().get(0).getName());
        assertTrue(resources.getSkills().get(0).isDisableModelInvocation());
        assertEquals("java-test", resources.getSkills().get(1).getName());
        assertEquals("Run Java tests", resources.getSkills().get(1).getDescription());
        assertEquals(visibleSkill.toAbsolutePath().normalize(), resources.getSkills().get(1).getLocation());
    }

    public void testLoadFallsBackToGlobalPromptWhenProjectPromptIsMissing() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-resource-test");
        Path agentDir = tempDir.resolve("agent");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(agentDir);
        Files.createDirectories(cwd);
        Files.writeString(agentDir.resolve("SYSTEM.md"), "Global system");

        PromptResources resources = new ResourceLoader(cwd, agentDir).load();

        assertEquals("Global system", resources.getSystemPrompt());
        assertNull(resources.getAppendSystemPrompt());
        assertTrue(resources.getContextFiles().isEmpty());
        assertTrue(resources.getSkills().isEmpty());
    }

    public void testLoadAlsoSupportsDotAgentProjectResources() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-resource-test");
        Path agentDir = tempDir.resolve("agent");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(agentDir);
        Files.createDirectories(cwd.resolve(".agent"));
        Files.writeString(cwd.resolve(".agent").resolve("SYSTEM.md"), "Dot agent system");
        Files.writeString(cwd.resolve(".agent").resolve("APPEND_SYSTEM.md"), "Dot agent append");
        Path skillFile = cwd.resolve(".agent").resolve("skills").resolve("review").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: review
                description: Review code changes
                ---
                """);

        PromptResources resources = new ResourceLoader(cwd, agentDir).load();

        assertEquals("Dot agent system", resources.getSystemPrompt());
        assertEquals("Dot agent append", resources.getAppendSystemPrompt());
        assertEquals(1, resources.getSkills().size());
        assertEquals("review", resources.getSkills().getFirst().getName());
        assertEquals(skillFile.toAbsolutePath().normalize(), resources.getSkills().getFirst().getLocation());
    }
}
