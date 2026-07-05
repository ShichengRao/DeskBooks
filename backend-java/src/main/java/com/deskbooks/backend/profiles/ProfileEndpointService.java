package com.deskbooks.backend.profiles;

import java.sql.SQLException;
import java.util.NoSuchElementException;

import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.onboarding.OnboardingService;
import com.deskbooks.backend.profiles.ProfileController.ProfileActivateRequest;
import com.deskbooks.backend.profiles.ProfileController.ProfileCreateRequest;
import com.deskbooks.backend.profiles.ProfileController.ProfileDuplicateRequest;
import com.deskbooks.backend.profiles.ProfileController.ProfileListResponse;
import com.deskbooks.backend.profiles.ProfileController.ProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
final class ProfileEndpointService {
    private final ProfileRegistry registry;
    private final OnboardingService onboardingService;
    private final ProfileResponses responses = new ProfileResponses();

    ProfileEndpointService(ProfileRegistry registry, OnboardingService onboardingService) {
        this.registry = registry;
        this.onboardingService = onboardingService;
    }

    ProfileListResponse listProfiles() {
        return profileList();
    }

    ProfileListResponse createProfile(ProfileCreateRequest body) {
        ProfileInfo created = registry.createProfile(body.name());
        registry.setActiveProfile(created.slug());
        seedStarterData(body);
        return profileList();
    }

    ProfileListResponse duplicateProfile(ProfileDuplicateRequest body) {
        try {
            registry.duplicateProfile(body.name(), body.sourceSlug());
            return profileList();
        } catch (NoSuchElementException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "source profile not found");
        }
    }

    ProfileListResponse activateProfile(ProfileActivateRequest body) {
        try {
            registry.setActiveProfile(body.slug());
            return profileList();
        } catch (NoSuchElementException exception) {
            throw profileNotFound();
        }
    }

    ProfileResponse deleteProfile(String slug) {
        try {
            return responses.from(registry.deleteProfile(slug));
        } catch (NoSuchElementException exception) {
            throw profileNotFound();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private void seedStarterData(ProfileCreateRequest body) {
        if (body.seedStarterData() != null && !body.seedStarterData()) {
            return;
        }
        try {
            onboardingService.seedActiveProfile();
        } catch (SQLException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    private ProfileListResponse profileList() {
        return responses.list(registry.listProfiles());
    }

    private ApiException profileNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "profile not found");
    }
}
