package io.github.lingjiuu.provider.protocol;

import java.util.List;

public interface RequestFinalizer {

    List<NormalizedRequestMessage> finalizeMessages(List<NormalizedRequestMessage> messages);
}
