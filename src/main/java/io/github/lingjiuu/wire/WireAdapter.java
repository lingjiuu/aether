package io.github.lingjiuu.wire;

import io.github.lingjiuu.model.ModelSelection;

public interface WireAdapter {

    String name();

    WireSession openSession(ModelSelection selection);
}
