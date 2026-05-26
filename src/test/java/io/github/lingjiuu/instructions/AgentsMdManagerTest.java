package io.github.lingjiuu.instructions;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class AgentsMdManagerTest extends TestCase {

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

        AgentsMdInstructions instructions = new AgentsMdManager(child, agentDir).load();

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

        AgentsMdInstructions instructions = new AgentsMdManager(workspace, null).load();

        assertEquals("local rules", instructions.text());
        assertEquals(1, instructions.sources().size());
        assertEquals(workspace.resolve("AGENTS.override.md").toAbsolutePath().normalize(), instructions.sources().getFirst());
    }
}
