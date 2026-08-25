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
        assertTrue(AnimalGender.MALE.name().equals("MALE"));
        assertTrue(AnimalGender.FEMALE.name().equals("FEMALE"));
    }
}
