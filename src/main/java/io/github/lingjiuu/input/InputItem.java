package io.github.lingjiuu.input;

public interface InputItem {

    Kind kind();

    enum Kind {
        TEXT,
        FILE,
        LOCAL_IMAGE,
        SKILL
    }
}
