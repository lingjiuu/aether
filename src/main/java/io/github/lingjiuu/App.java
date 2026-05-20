package io.github.lingjiuu;

import io.github.lingjiuu.cli.ConsoleInputLoop;
import io.github.lingjiuu.session.SessionFactory;

public class App {
    public static void main(String[] args) {
        SessionFactory sessionFactory = SessionFactory.createDefault();
        ConsoleInputLoop inputLoop = new ConsoleInputLoop(sessionFactory);
        String oneShotInput = String.join(" ", args).trim();
        if (!oneShotInput.isBlank()) {
            inputLoop.runOneShot(oneShotInput);
            return;
        }
        inputLoop.run();
    }
}
