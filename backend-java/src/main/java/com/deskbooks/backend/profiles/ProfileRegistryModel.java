package com.deskbooks.backend.profiles;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

record Registry(String active, List<RegistryProfile> profiles) {
}

record RegistryProfile(String slug, String name, @JsonProperty("db_file") String dbFile) {
}
