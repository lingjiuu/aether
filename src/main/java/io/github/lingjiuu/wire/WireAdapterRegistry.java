package io.github.lingjiuu.wire;

import java.util.LinkedHashMap;
import java.util.Map;

public class WireAdapterRegistry {

    private final Map<String, WireAdapter> providers = new LinkedHashMap<>();

    public WireAdapterRegistry register(WireAdapter provider) {
        providers.put(provider.name(), provider);
        return this;
    }

    public WireAdapter require(String api) {
        WireAdapter provider = providers.get(api);
        if (provider == null) {
            throw new IllegalArgumentException("No wire adapter registered for api: " + api);
        }
        return provider;
    }
}
