package io.github.lingjiuu.provider;

import java.util.LinkedHashMap;
import java.util.Map;

public class ProviderRegistry {

    private final Map<String, Provider> providers = new LinkedHashMap<>();

    public ProviderRegistry register(Provider provider) {
        providers.put(provider.name(), provider);
        return this;
    }

    public Provider require(String api) {
        Provider provider = providers.get(api);
        if (provider == null) {
            throw new IllegalArgumentException("No provider registered for api: " + api);
        }
        return provider;
    }
}
