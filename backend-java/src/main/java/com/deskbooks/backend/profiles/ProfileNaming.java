package com.deskbooks.backend.profiles;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ProfileNaming {
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private final ProfilePaths paths;

    ProfileNaming(ProfilePaths paths) {
        this.paths = paths;
    }

    RegistryProfile newProfile(Registry registry, String name) {
        String slug = uniqueSlug(registry, name);
        return new RegistryProfile(slug, normalizedName(name, slug), paths.dbFileForSlug(slug));
    }

    private String uniqueSlug(Registry registry, String name) {
        String baseSlug = slugify(name);
        Set<String> existing = registry.profiles().stream()
                .map(RegistryProfile::slug)
                .collect(java.util.stream.Collectors.toSet());
        String slug = baseSlug;
        int suffix = 2;
        while (existing.contains(slug)) {
            slug = baseSlug + "-" + suffix;
            suffix++;
        }
        return slug;
    }

    private String slugify(String name) {
        String slug = NON_SLUG_CHARS.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug.isBlank() ? "profile" : slug;
    }

    private String normalizedName(String name, String fallback) {
        String trimmed = name.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
