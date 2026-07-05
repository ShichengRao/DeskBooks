package com.deskbooks.backend.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuleDescriptionGeneralizerTest {
    private final RuleDescriptionGeneralizer generalizer = new RuleDescriptionGeneralizer();

    @Test
    void generalizePreservesLegacyDescriptionRules() {
        assertEquals("DD", generalizer.generalize("DD DoorDash 123456"));
        assertEquals("METRO COFFEE 001", generalizer.generalize("METRO COFFEE 001"));
        assertEquals("Nyct Paygo", generalizer.generalize("MTA NYCT PAYGO 991234"));
    }

    @Test
    void patternForEscapesRegexMetacharactersBetweenTokens() {
        assertEquals("A\\.B.*Cafe\\?", generalizer.patternFor("A.B Cafe?"));
    }
}
