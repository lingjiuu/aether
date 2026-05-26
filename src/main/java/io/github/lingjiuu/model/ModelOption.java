package io.github.lingjiuu.model;

import io.github.lingjiuu.provider.ProviderEndpoint;

import java.util.List;

public record ModelOption(
        String providerId,
        String modelId,
        String name,
        String api,
        Long contextWindowTokens,
        Long autoCompactTokenLimit,
        List<String> input
) {

    public ModelOption {
        input = input == null ? List.of() : List.copyOf(input);
    }

    public static ModelOption from(ModelSelection selection) {
        if (selection == null || selection.model() == null) {
            return null;
        }

        ModelInfo model = selection.model();
        ProviderEndpoint endpoint = selection.endpoint();
        return new ModelOption(
                endpoint == null ? null : endpoint.providerId(),
                model.getId(),
                model.getName(),
                endpoint == null ? null : endpoint.wireApi(),
                model.getContextWindowTokens(),
                model.getAutoCompactTokenLimit(),
                model.getInput()
        );
    }
}
