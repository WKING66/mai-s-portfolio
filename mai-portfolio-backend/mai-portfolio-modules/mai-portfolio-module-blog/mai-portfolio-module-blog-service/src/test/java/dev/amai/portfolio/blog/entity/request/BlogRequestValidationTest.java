package dev.amai.portfolio.blog.entity.request;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.validation.ValidationGroups;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlogRequestValidationTest {
    @Test
    void updateGroupKeepsDefaultFieldConstraints() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new BlogRequest(null, "", "Upper Case", "", "", null, null, true, false);
            var fields = factory.getValidator().validate(request, ValidationGroups.Update.class)
                    .stream().map(error -> error.getPropertyPath().toString()).toList();
            assertThat(fields).contains("revision", "requestId", "slug", "content", "tagIds");
        }
    }

    @Test
    void emptyDraftIsValidButPublishingRemainsBusinessDecision() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new BlogRequest(null, UUID.randomUUID().toString(),
                    "", "", "", "", List.of(), true, false);
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }
}
