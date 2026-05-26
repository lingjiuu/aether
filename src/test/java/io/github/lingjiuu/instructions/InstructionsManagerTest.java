package io.github.lingjiuu.instructions;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class InstructionsManagerTest extends TestCase {

    public void testLoadMergesGlobalAndProjectInstructionsRootFirst() throws Exception {
        Path root = Files.createTempDirectory("aether-agents-md");
        Path agentDir = root.resolve("agent");
        Path workspace = root.resolve("workspace");
        Path child = workspace.resolve("child");
        Files.createDirectories(agentDir);
        Files.createDirectories(child);
        Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(agentDir.resolve("AGENTS.md"), "global rules", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("AGENTS.md"), "workspace rules", StandardCharsets.UTF_8);
        Files.writeString(child.resolve("AGENTS.md"), "child rules", StandardCharsets.UTF_8);

        AgentsMdInstructions instructions = new InstructionsManager(child, agentDir).loadAgentsMdInstructions();

        assertEquals("""
                global rules

                --- project-doc ---

                workspace rules

                --- project-doc ---

                child rules""".strip(), instructions.text());
        assertEquals(3, instructions.sources().size());
        assertEquals(agentDir.resolve("AGENTS.md").toAbsolutePath().normalize(), instructions.sources().get(0));
        assertEquals(workspace.resolve("AGENTS.md").toAbsolutePath().normalize(), instructions.sources().get(1));
        assertEquals(child.resolve("AGENTS.md").toAbsolutePath().normalize(), instructions.sources().get(2));
    }

    public void testLoadPrefersLocalOverrideInDirectory() throws Exception {
        Path root = Files.createTempDirectory("aether-agents-md-override");
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace);
        Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("AGENTS.md"), "shared rules", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("AGENTS.override.md"), "local rules", StandardCharsets.UTF_8);

        AgentsMdInstructions instructions = new InstructionsManager(workspace, null).loadAgentsMdInstructions();

        assertEquals("local rules", instructions.text());
        assertEquals(1, instructions.sources().size());
        assertEquals(workspace.resolve("AGENTS.override.md").toAbsolutePath().normalize(), instructions.sources().getFirst());
    }

    public void testBaseInstructionsFallsBackToDefault() throws Exception {
        Path root = Files.createTempDirectory("aether-instructions-default");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);

        InstructionsManager instructions = new InstructionsManager(cwd, agentDir);

        assertEquals(BaseInstructions.DEFAULT, instructions.baseInstructions());
        assertEquals("", instructions.developerInstructions());
    }

    public void testProjectInstructionFilesWinBeforeGlobalFiles() throws Exception {
        Path root = Files.createTempDirectory("aether-instructions-project");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Files.createDirectories(cwd.resolve(".aether"));
        Files.createDirectories(cwd.resolve(".agent"));
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("SYSTEM.md"), "global base", StandardCharsets.UTF_8);
        Files.writeString(cwd.resolve(".aether").resolve("SYSTEM.md"), "project base", StandardCharsets.UTF_8);
        Files.writeString(cwd.resolve(".agent").resolve("APPEND_SYSTEM.md"), "project developer", StandardCharsets.UTF_8);

        InstructionsManager instructions = new InstructionsManager(cwd, agentDir);

        assertEquals("project base", instructions.baseInstructions());
        assertEquals("project developer", instructions.developerInstructions());
    }
}
