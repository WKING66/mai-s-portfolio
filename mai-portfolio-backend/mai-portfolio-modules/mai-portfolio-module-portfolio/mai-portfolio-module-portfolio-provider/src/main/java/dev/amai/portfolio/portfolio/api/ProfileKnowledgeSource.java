package dev.amai.portfolio.portfolio.api;

import java.util.List;

public interface ProfileKnowledgeSource {
    record Profile(
            long id,

            String displayName,

            String email,

            String githubUrl,

            String headline,

            String intro) {}

    Profile profile(long id);

    List<Profile> profiles();

    boolean lock(long id);
}
