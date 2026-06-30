package tech.skullprogrammer.engine;

import net.datafaker.Faker;
import tech.skullprogrammer.model.MockProfile;

public record GenerationContext(
        int depth,
        Faker faker,
        String endpointPath,
        String method,
        String fieldPath,
        MockProfile.EndpointOverride override,
        double optionalProbability,
        int maxDepth,
        int defaultArrayCount,
        boolean useSemantic
) {
    public GenerationContext nested(String fieldName) {
        String newPath = fieldPath.isEmpty() ? fieldName : fieldPath + "." + fieldName;
        return new GenerationContext(depth + 1, faker, endpointPath, method, newPath,
                override, optionalProbability, maxDepth, defaultArrayCount, useSemantic);
    }
}
