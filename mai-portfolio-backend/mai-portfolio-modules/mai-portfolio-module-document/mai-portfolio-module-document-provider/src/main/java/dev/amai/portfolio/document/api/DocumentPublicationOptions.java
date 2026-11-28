package dev.amai.portfolio.document.api;

public record DocumentPublicationOptions(
        Integer visibilityLevel,

        Boolean aiIndexEnabled,

        Integer indexBuildMode,

        boolean purgeVectorPayload) {}
