package io.github.lingjiuu;

import io.github.lingjiuu.ui.cli.ConsoleInputLoop;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.session.SessionOptions;
import io.github.lingjiuu.transport.stdio.StdioAetherServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class App {
    public static void main(String[] args) {
        LaunchOptions options = LaunchOptions.parse(args);
        SessionFactory sessionFactory = SessionFactory.createDefault();
        SessionOptions sessionOptions = options.cwd() == null
                ? SessionOptions.defaults()
                : SessionOptions.cwd(options.cwd());
        if (options.stdio()) {
            new StdioAetherServer(sessionFactory, sessionOptions).run();
            return;
        }
        ConsoleInputLoop inputLoop = new ConsoleInputLoop(sessionFactory, sessionOptions);
        String oneShotInput = String.join(" ", options.remainingArgs()).trim();
        if (!oneShotInput.isBlank()) {
            inputLoop.runOneShot(oneShotInput);
            return;
        }
        inputLoop.run();
    }

    private record LaunchOptions(boolean stdio, Path cwd, List<String> remainingArgs) {

        private static LaunchOptions parse(String[] args) {
            boolean stdio = false;
            Path cwd = envCwd();
            List<String> remaining = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--stdio".equals(arg)) {
                    stdio = true;
                    continue;
                }
                if ("--cwd".equals(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--cwd requires a directory");
                    }
                    cwd = Path.of(args[++i]);
                    continue;
                }
                if (arg.startsWith("--cwd=")) {
                    cwd = Path.of(arg.substring("--cwd=".length()));
                    continue;
                }
                remaining.add(arg);
            }

            return new LaunchOptions(stdio, cwd, List.copyOf(remaining));
        }

        private static Path envCwd() {
            String value = System.getenv("AETHER_SESSION_CWD");
            return value == null || value.isBlank() ? null : Path.of(value);
        }
    }
}
