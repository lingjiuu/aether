package io.github.lingjiuu.context;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record EnvironmentContext(Path cwd, String shell, LocalDate currentDate, ZoneId timezone) {

    public static EnvironmentContext from(Path cwd) {
        ZoneId timezone = ZoneId.systemDefault();
        return new EnvironmentContext(normalize(cwd), defaultShell(), LocalDate.now(timezone), timezone);
    }

    public List<Field> fullFields() {
        return List.of(
                new Field("cwd", renderPath(cwd)),
                new Field("shell", nullToDefault(shell, "sh")),
                new Field("current_date", currentDate == null ? "" : currentDate.toString()),
                new Field("timezone", timezone == null ? "" : timezone.getId())
        );
    }

    public List<Field> diffFields(EnvironmentContext previous) {
        if (previous == null) {
            return fullFields();
        }

        List<Field> fields = new ArrayList<>();
        if (!Objects.equals(cwd, previous.cwd())) {
            fields.add(new Field("cwd", renderPath(cwd)));
        }
        if (!Objects.equals(shell, previous.shell())) {
            fields.add(new Field("shell", nullToDefault(shell, "sh")));
        }
        if (!Objects.equals(currentDate, previous.currentDate())) {
            fields.add(new Field("current_date", currentDate == null ? "" : currentDate.toString()));
        }
        if (!Objects.equals(timezone, previous.timezone())) {
            fields.add(new Field("timezone", timezone == null ? "" : timezone.getId()));
        }
        return fields;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String renderPath(Path path) {
        return path == null ? "<unknown>" : path.toString();
    }

    private static String defaultShell() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            return "sh";
        }
        Path shellPath = Path.of(shell);
        Path fileName = shellPath.getFileName();
        return fileName == null ? shell : fileName.toString();
    }

    private static String nullToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Field(String name, String value) {
    }
}
