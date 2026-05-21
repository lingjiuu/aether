package io.github.lingjiuu.input;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record TurnInput(List<InputItem> items) {

    public TurnInput {
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("turn input must contain at least one item");
        }
    }

    public static TurnInput ofText(String text) {
        return new TurnInput(List.of(new TextInput(text)));
    }

    public static TurnInput ofItems(List<InputItem> items) {
        return new TurnInput(items);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<InputItem> items = new ArrayList<>();

        public Builder text(String text) {
            items.add(new TextInput(text));
            return this;
        }

        public Builder file(Path path) {
            items.add(new FileInput(path));
            return this;
        }

        public Builder localImage(Path path) {
            items.add(new LocalImageInput(path));
            return this;
        }

        public Builder skill(String name, Path path) {
            items.add(new SkillInput(name, path));
            return this;
        }

        public Builder item(InputItem item) {
            if (item != null) {
                items.add(item);
            }
            return this;
        }

        public TurnInput build() {
            return new TurnInput(items);
        }
    }
}
