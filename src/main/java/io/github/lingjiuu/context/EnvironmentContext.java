package io.github.lingjiuu.context;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record EnvironmentContext(Path cwd, LocalDate currentDate, ZoneId timezone) {

    public static EnvironmentContext from(Path cwd) {
        ZoneId timezone = ZoneId.systemDefault();
        return new EnvironmentContext(normalize(cwd), LocalDate.now(timezone), timezone);
    }

    public List<String> fullLines() {
        return List.of(
                "- cwd: " + renderPath(cwd),
                "- current_date: " + currentDate,
                "- timezone: " + timezone.getId()
        );
    }

    public List<String> diffLines(EnvironmentContext previous) {
        if (previous == null) {
            return fullLines();
        }

        List<String> lines = new ArrayList<>();
        if (!Objects.equals(cwd, previous.cwd())) {
            lines.add("- cwd: " + renderPath(cwd));
        }
        if (!Objects.equals(currentDate, previous.currentDate())) {
            lines.add("- current_date: " + currentDate);
        }
        if (!Objects.equals(timezone, previous.timezone())) {
            lines.add("- timezone: " + timezone.getId());
        }
        return lines;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String renderPath(Path path) {
        return path == null ? "<unknown>" : path.toString();
    }
}
