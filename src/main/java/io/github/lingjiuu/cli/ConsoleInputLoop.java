package io.github.lingjiuu.cli;

import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiCommandType;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.session.SessionOptions;
import io.github.lingjiuu.ui.UiRuntime;

import java.util.NoSuchElementException;
import java.util.Scanner;

public class ConsoleInputLoop {

    private final ConsoleRenderer renderer;
    private final ConsoleApprovalHandler approvalHandler;
    private final Scanner scanner;
    private final UiRuntime uiRuntime;
    private boolean running = true;

    public ConsoleInputLoop(SessionFactory sessionFactory) {
        this(sessionFactory, SessionOptions.defaults());
    }

    public ConsoleInputLoop(SessionFactory sessionFactory, SessionOptions defaultSessionOptions) {
        this(sessionFactory, defaultSessionOptions, new Scanner(System.in), new ConsoleRenderer(), null);
    }

    ConsoleInputLoop(
            SessionFactory sessionFactory,
            SessionOptions defaultSessionOptions,
            Scanner scanner,
            ConsoleRenderer renderer,
            ConsoleApprovalHandler approvalHandler
    ) {
        if (sessionFactory == null) {
            throw new IllegalArgumentException("sessionFactory must not be null");
        }
        Scanner resolvedScanner = scanner == null ? new Scanner(System.in) : scanner;
        this.scanner = resolvedScanner;
        this.renderer = renderer == null ? new ConsoleRenderer() : renderer;
        this.approvalHandler = approvalHandler == null ? new ConsoleApprovalHandler(resolvedScanner) : approvalHandler;
        this.uiRuntime = new UiRuntime(sessionFactory, defaultSessionOptions, this.renderer, this.approvalHandler);
    }

    public void run() {
        printBanner();
        while (running) {
            System.out.print("> ");
            String line;
            try {
                line = scanner.nextLine();
            } catch (NoSuchElementException e) {
                System.out.println();
                uiRuntime.close();
                return;
            }

            if (line == null) {
                uiRuntime.close();
                return;
            }
            String input = line.trim();
            if (input.isBlank()) {
                continue;
            }
            if (handleCommand(input)) {
                continue;
            }
            submitAndWait(UiCommand.submitUserInput(input));
        }
    }

    public void runOneShot(String input) {
        if (input == null || input.isBlank()) {
            return;
        }
        UiCommandAck ack = uiRuntime.submit(UiCommand.submitUserInput(input.trim()));
        handleAck(ack);
        if (ack != null && ack.accepted()) {
            uiRuntime.waitForIdle();
        }
    }

    private boolean handleCommand(String input) {
        if (!input.startsWith("/")) {
            return false;
        }

        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "/exit", "/quit" -> {
                System.out.println("[SESSION] bye");
                uiRuntime.close();
                running = false;
                return true;
            }
            case "/session" -> {
                System.out.println("[SESSION] id=" + uiRuntime.sessionId());
                System.out.println("[SESSION] messages=" + uiRuntime.messageCount());
                return true;
            }
            case "/compact" -> {
                submitAndWait(UiCommand.simple(UiCommandType.COMPACT));
                return true;
            }
            case "/continue" -> {
                submitAndWait(UiCommand.simple(UiCommandType.CONTINUE));
                return true;
            }
            case "/resume" -> {
                resume(argument);
                return true;
            }
            case "/new" -> {
                handleAck(uiRuntime.submit(UiCommand.simple(UiCommandType.NEW_SESSION)));
                System.out.println("[SESSION] new id=" + uiRuntime.sessionId());
                return true;
            }
            case "/skills" -> {
                skills(argument);
                return true;
            }
            case "/help" -> {
                printHelp();
                return true;
            }
            default -> {
                System.out.println("[ERROR] unknown command: " + command);
                printHelp();
                return true;
            }
        }
    }

    private void resume(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            System.out.println("[ERROR] usage: /resume <session-id>");
            return;
        }
        UiCommandAck ack = uiRuntime.submit(UiCommand.resumeSession(sessionId));
        handleAck(ack);
        if (ack != null && ack.accepted()) {
            renderHistory(ack);
            System.out.println("[SESSION] resumed id=" + uiRuntime.sessionId());
            System.out.println("[SESSION] messages=" + uiRuntime.messageCount());
        }
    }

    private void skills(String argument) {
        var skills = uiRuntime.availableSkills();
        if (skills.isEmpty()) {
            System.out.println("[SKILLS] none");
            return;
        }
        for (var skill : skills) {
            System.out.println("[SKILLS] " + skill.getName()
                    + " - " + skill.getDescription()
                    + " (" + skill.getLocation() + ")");
        }
    }

    private void handleAck(UiCommandAck ack) {
        if (ack != null && !ack.accepted()) {
            System.out.println("[ERROR] " + ack.message());
        }
    }

    private void submitAndWait(UiCommand command) {
        UiCommandAck ack = uiRuntime.submit(command);
        handleAck(ack);
        if (ack != null && ack.accepted()) {
            uiRuntime.waitForIdle();
        }
    }

    private void renderHistory(UiCommandAck ack) {
        if (ack == null || ack.history() == null) {
            return;
        }
        renderer.renderHistory(ack.history());
    }

    private void printBanner() {
        System.out.println("Aether demo CLI");
        System.out.println("[SESSION] id=" + uiRuntime.sessionId());
        printHelp();
    }

    private void printHelp() {
        System.out.println("Commands: /session, /resume <id>, /new, /compact, /continue, /skills, /exit");
    }
}
