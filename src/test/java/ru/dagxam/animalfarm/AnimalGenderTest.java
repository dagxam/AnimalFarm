package ru.dagxam.animalfarm;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimalGenderTest {
    @Test
    void genderEnumContainsExactlyMaleAndFemale() {
        assertEquals(EnumSet.of(AnimalGender.MALE, AnimalGender.FEMALE), EnumSet.allOf(AnimalGender.class));
    }

    @Test
    void maleAndFemaleAreDifferentValues() {
        assertFalse(AnimalGender.MALE == AnimalGender.FEMALE);
        assertEquals("MALE", AnimalGender.MALE.name());
        assertEquals("FEMALE", AnimalGender.FEMALE.name());
    }

    @Test
    void breedingRequiresMaleAndFemaleWhenRuleEnabled() {
        assertTrue(AnimalGenderRules.canBreed(AnimalGender.MALE, AnimalGender.FEMALE, true));
        assertTrue(AnimalGenderRules.canBreed(AnimalGender.FEMALE, AnimalGender.MALE, true));
        assertFalse(AnimalGenderRules.canBreed(AnimalGender.MALE, AnimalGender.MALE, true));
        assertFalse(AnimalGenderRules.canBreed(AnimalGender.FEMALE, AnimalGender.FEMALE, true));
        assertFalse(AnimalGenderRules.canBreed(null, AnimalGender.FEMALE, true));
        assertFalse(AnimalGenderRules.canBreed(AnimalGender.MALE, null, true));
    }

    @Test
    void breedingCanBeDisabledByConfiguration() {
        assertTrue(AnimalGenderRules.canBreed(AnimalGender.MALE, AnimalGender.MALE, false));
        assertTrue(AnimalGenderRules.canBreed(AnimalGender.FEMALE, AnimalGender.FEMALE, false));
        assertTrue(AnimalGenderRules.canBreed(null, null, false));
    }

    @Test
    void milkIsFemaleOnlyWhenRuleEnabled() {
        assertTrue(AnimalGenderRules.canGiveMilk(AnimalGender.FEMALE, true));
        assertFalse(AnimalGenderRules.canGiveMilk(AnimalGender.MALE, true));
        assertFalse(AnimalGenderRules.canGiveMilk(null, true));
    }

    @Test
    void milkRestrictionCanBeDisabled() {
        assertTrue(AnimalGenderRules.canGiveMilk(AnimalGender.FEMALE, false));
        assertTrue(AnimalGenderRules.canGiveMilk(AnimalGender.MALE, false));
        assertTrue(AnimalGenderRules.canGiveMilk(null, false));
    }
}
