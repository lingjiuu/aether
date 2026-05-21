package io.github.lingjiuu.cli;

import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionFactory;

import java.util.NoSuchElementException;
import java.util.Scanner;

public class ConsoleInputLoop {

    private final SessionFactory sessionFactory;
    private final ConsoleRenderer renderer;
    private final ConsoleApprovalHandler approvalHandler;
    private final Scanner scanner;
    private Session session;
    private boolean running = true;

    public ConsoleInputLoop(SessionFactory sessionFactory) {
        this(sessionFactory, new Scanner(System.in), new ConsoleRenderer(), null);
    }

    ConsoleInputLoop(
            SessionFactory sessionFactory,
            Scanner scanner,
            ConsoleRenderer renderer,
            ConsoleApprovalHandler approvalHandler
    ) {
        if (sessionFactory == null) {
            throw new IllegalArgumentException("sessionFactory must not be null");
        }
        Scanner resolvedScanner = scanner == null ? new Scanner(System.in) : scanner;
        this.sessionFactory = sessionFactory;
        this.scanner = resolvedScanner;
        this.renderer = renderer == null ? new ConsoleRenderer() : renderer;
        this.approvalHandler = approvalHandler == null ? new ConsoleApprovalHandler(resolvedScanner) : approvalHandler;
        this.session = configure(sessionFactory.openSession());
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
                closeCurrentSession();
                return;
            }

            if (line == null) {
                closeCurrentSession();
                return;
            }
            String input = line.trim();
            if (input.isBlank()) {
                continue;
            }
            if (handleCommand(input)) {
                continue;
            }
            session.prompt(input);
        }
    }

    public void runOneShot(String input) {
        if (input == null || input.isBlank()) {
            return;
        }
        session.prompt(input.trim());
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
                closeCurrentSession();
                running = false;
                return true;
            }
            case "/session" -> {
                System.out.println("[SESSION] id=" + session.sessionId());
                System.out.println("[SESSION] messages=" + session.messages().size());
                return true;
            }
            case "/compact" -> {
                session.compact();
                return true;
            }
            case "/continue" -> {
                session.continueSession();
                return true;
            }
            case "/resume" -> {
                resume(argument);
                return true;
            }
            case "/new" -> {
                switchSession(sessionFactory.openSession());
                System.out.println("[SESSION] new id=" + session.sessionId());
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
        if (!sessionFactory.config().transcriptStore().exists(sessionId)) {
            System.out.println("[ERROR] transcript not found for session: " + sessionId);
            return;
        }
        try {
            switchSession(sessionFactory.resumeSession(sessionId));
            System.out.println("[SESSION] resumed id=" + session.sessionId());
            System.out.println("[SESSION] messages=" + session.messages().size());
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }

    private void skills(String argument) {
        if ("reload".equals(argument)) {
            session.reloadSkills();
        }
        var skills = session.availableSkills();
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

    private void switchSession(Session nextSession) {
        closeCurrentSession();
        session = configure(nextSession);
    }

    private void closeCurrentSession() {
        if (session != null) {
            session.close();
        }
    }

    private Session configure(Session session) {
        session.subscribe(renderer);
        session.setApprovalHandler(approvalHandler);
        return session;
    }

    private void printBanner() {
        System.out.println("Aether demo CLI");
        System.out.println("[SESSION] id=" + session.sessionId());
        printHelp();
    }

    private void printHelp() {
        System.out.println("Commands: /session, /resume <id>, /new, /compact, /continue, /skills, /skills reload, /exit");
    }
}
