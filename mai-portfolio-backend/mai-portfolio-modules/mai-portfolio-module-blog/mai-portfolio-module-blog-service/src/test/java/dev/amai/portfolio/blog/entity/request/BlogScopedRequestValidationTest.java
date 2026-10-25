package dev.amai.portfolio.blog.entity.request;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.validation.ValidationGroups;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class BlogScopedRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final String REQUEST_ID = "9872cfc3-65e1-4bfb-968d-09a3f0663900";

    @AfterAll
    static void closeValidation() {
        FACTORY.close();
    }

    @Test
    void bodyCanBeSavedWithoutAnyArticleProperties() {
        assertThat(FACTORY.getValidator().validate(new BlogContentRequest(5L, REQUEST_ID, "", false))).isEmpty();
    }

    @Test
    void propertiesRequireExplicitDownloadChoiceAndTags() {
        var request = new BlogPropertiesRequest(5L, REQUEST_ID, null, null, null, null, null);
        var fields = FACTORY.getValidator().validate(request)
                .stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();
        assertThat(fields).containsExactlyInAnyOrder("tagIds", "allowDownload");
    }

    @Test
    void legacySlugIsValidatedOnlyWhenServiceSelectsChangedSlugGroup() {
        var request = new BlogPropertiesRequest(5L, REQUEST_ID, "", "", "Legacy.Slug", List.of(), true);
        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(FACTORY.getValidator().validateProperty(request, "slug", ValidationGroups.Update.class))
                .isNotEmpty();
    }

    @Test
    void clearingOptionalPropertiesAndTagsIsAllowed() {
        var request = new BlogPropertiesRequest(5L, REQUEST_ID, null, "", null, List.of(), false);
        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(FACTORY.getValidator().validateProperty(request, "slug", ValidationGroups.Update.class))
                .isEmpty();
    }
}
