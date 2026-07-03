package com.deskbooks.backend.profiles;

import java.sql.SQLException;
import java.util.List;
import java.util.NoSuchElementException;

import com.deskbooks.backend.foundation.ApiException;
import com.deskbooks.backend.onboarding.OnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles")
class ProfileController {
    private final ProfileRegistry registry;
    private final OnboardingService onboardingService;

    ProfileController(ProfileRegistry registry, OnboardingService onboardingService) {
        this.registry = registry;
        this.onboardingService = onboardingService;
    }

    @GetMapping("")
    ProfileListResponse listProfiles() {
        return profileList();
    }

    @PostMapping("")
    ProfileListResponse createProfile(@Valid @RequestBody ProfileCreateRequest body) {
        ProfileInfo created = registry.createProfile(body.name());
        registry.setActiveProfile(created.slug());
        if (body.seedStarterData() == null || body.seedStarterData()) {
            try {
                onboardingService.seedActiveProfile();
            } catch (SQLException exception) {
                throw databaseError(exception);
            }
        }
        return profileList();
    }

    @PostMapping("/duplicate")
    ProfileListResponse duplicateProfile(@Valid @RequestBody ProfileDuplicateRequest body) {
        try {
            registry.duplicateProfile(body.name(), body.sourceSlug());
            return profileList();
        } catch (NoSuchElementException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "source profile not found");
        }
    }

    @PostMapping("/active")
    ProfileListResponse activateProfile(@Valid @RequestBody ProfileActivateRequest body) {
        try {
            registry.setActiveProfile(body.slug());
            return profileList();
        } catch (NoSuchElementException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "profile not found");
        }
    }

    @DeleteMapping("/{slug}")
    ProfileResponse deleteProfile(@PathVariable String slug) {
        try {
            return ProfileResponse.from(registry.deleteProfile(slug));
        } catch (NoSuchElementException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "profile not found");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private ProfileListResponse profileList() {
        List<ProfileInfo> profiles = registry.listProfiles();
        String activeSlug = profiles.stream()
                .filter(ProfileInfo::isActive)
                .findFirst()
                .or(() -> profiles.stream().findFirst())
                .map(ProfileInfo::slug)
                .orElse("");
        return new ProfileListResponse(
                profiles.stream().map(ProfileResponse::from).toList(),
                activeSlug);
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record ProfileCreateRequest(
            @NotBlank @Size(max = 80) String name,
            Boolean seedStarterData) {
    }

    record ProfileDuplicateRequest(
            @NotBlank @Size(max = 80) String name,
            String sourceSlug) {
    }

    record ProfileActivateRequest(@NotBlank String slug) {
    }

    record ProfileResponse(String slug, String name, String dbFile, boolean isActive) {
        static ProfileResponse from(ProfileInfo profile) {
            return new ProfileResponse(
                    profile.slug(),
                    profile.name(),
                    profile.dbFile(),
                    profile.isActive());
        }
    }

    record ProfileListResponse(List<ProfileResponse> profiles, String activeSlug) {
    }
}
