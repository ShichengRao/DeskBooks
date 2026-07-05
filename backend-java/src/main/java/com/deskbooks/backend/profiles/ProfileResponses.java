package com.deskbooks.backend.profiles;

import java.util.List;

import com.deskbooks.backend.profiles.ProfileController.ProfileListResponse;
import com.deskbooks.backend.profiles.ProfileController.ProfileResponse;

final class ProfileResponses {
    ProfileListResponse list(List<ProfileInfo> profiles) {
        return new ProfileListResponse(
                profiles.stream().map(this::from).toList(),
                activeSlug(profiles));
    }

    ProfileResponse from(ProfileInfo profile) {
        return new ProfileResponse(
                profile.slug(),
                profile.name(),
                profile.dbFile(),
                profile.isActive());
    }

    private String activeSlug(List<ProfileInfo> profiles) {
        return profiles.stream()
                .filter(ProfileInfo::isActive)
                .findFirst()
                .or(() -> profiles.stream().findFirst())
                .map(ProfileInfo::slug)
                .orElse("");
    }
}
