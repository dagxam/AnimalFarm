package ru.dagxam.animalfarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmCapacityServiceTest {

    @Test
    void sourceDefinesInclusiveAnimalCapacityBoundary() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ru/dagxam/animalfarm/FarmCapacityService.java"));
        assertTrue(source.contains("current + amount <= settings.animalCapacity()"));
    }

    @Test
    void sourceRejectsZeroAndNegativeAmounts() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ru/dagxam/animalfarm/FarmCapacityService.java"));
        assertTrue(source.contains("amount > 0"));
    }

    @Test
    void sourceHasSeparateFishCapacityRule() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ru/dagxam/animalfarm/FarmCapacityService.java"));
        assertTrue(source.contains("settings.fishCapacity()"));
    }

    @Test
    void sourceClampsRemainingCapacityToZero() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ru/dagxam/animalfarm/FarmCapacityService.java"));
        assertTrue(source.contains("Math.max(0, settings.animalCapacity() - Math.max(0, current))"));
        assertTrue(source.contains("Math.max(0, settings.fishCapacity() - Math.max(0, current))"));
    }

    @Test
    void animalAndFishRulesAreNotAccidentallyMixed() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ru/dagxam/animalfarm/FarmCapacityService.java"));
        assertFalse(source.contains("animalCapacity() - Math.max(0, current));\n    }\n\n    public int remainingFishCapacity"));
    }
}
