package io.github.lingjiuu;

import io.github.lingjiuu.cli.ConsoleInputLoop;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.transport.stdio.StdioAetherServer;

public class App {
    public static void main(String[] args) {
        SessionFactory sessionFactory = SessionFactory.createDefault();
        if (args.length > 0 && "--stdio".equals(args[0])) {
            new StdioAetherServer(sessionFactory).run();
            return;
        }
        ConsoleInputLoop inputLoop = new ConsoleInputLoop(sessionFactory);
        String oneShotInput = String.join(" ", args).trim();
        if (!oneShotInput.isBlank()) {
            inputLoop.runOneShot(oneShotInput);
            return;
        }
        inputLoop.run();
    }
}
