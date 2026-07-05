package com.deskbooks.backend.profiles;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final ProfileEndpointService profiles;

    ProfileController(ProfileEndpointService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("")
    ProfileListResponse listProfiles() {
        return profiles.listProfiles();
    }

    @PostMapping("")
    ProfileListResponse createProfile(@Valid @RequestBody ProfileCreateRequest body) {
        return profiles.createProfile(body);
    }

    @PostMapping("/duplicate")
    ProfileListResponse duplicateProfile(@Valid @RequestBody ProfileDuplicateRequest body) {
        return profiles.duplicateProfile(body);
    }

    @PostMapping("/active")
    ProfileListResponse activateProfile(@Valid @RequestBody ProfileActivateRequest body) {
        return profiles.activateProfile(body);
    }

    @DeleteMapping("/{slug}")
    ProfileResponse deleteProfile(@PathVariable String slug) {
        return profiles.deleteProfile(slug);
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
    }

    record ProfileListResponse(List<ProfileResponse> profiles, String activeSlug) {
    }
}
