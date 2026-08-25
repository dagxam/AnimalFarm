package ru.dagxam.animalfarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmCapacityServiceTest {

    private static final java.nio.file.Path SOURCE = java.nio.file.Path.of(
            "src/main/java/ru/dagxam/animalfarm/FarmCapacityService.java");

    private String source() throws Exception {
        return java.nio.file.Files.readString(SOURCE);
    }

    @Test
    void sourceDefinesInclusiveAnimalCapacityBoundary() throws Exception {
        assertTrue(source().contains("current + amount <= settings.animalCapacity()"));
    }

    @Test
    void sourceRejectsZeroAndNegativeAmounts() throws Exception {
        assertTrue(source().contains("amount > 0"));
    }

    @Test
    void sourceHasSeparateFishCapacityRule() throws Exception {
        String source = source();
        assertTrue(source.contains("public boolean canAddFish(int current, int amount)"));
        assertTrue(source.contains("current + amount <= settings.fishCapacity()"));
        assertTrue(source.contains("public int remainingFishCapacity(int current)"));
        assertTrue(source.contains("settings.fishCapacity() - Math.max(0, current)"));
    }

    @Test
    void sourceClampsRemainingCapacityToZero() throws Exception {
        String source = source();
        assertTrue(source.contains("Math.max(0, settings.animalCapacity() - Math.max(0, current))"));
        assertTrue(source.contains("Math.max(0, settings.fishCapacity() - Math.max(0, current))"));
    }

    @Test
    void animalAndFishRulesAreNotAccidentallyMixed() throws Exception {
        String source = source();
        int animalStart = source.indexOf("public boolean canAddAnimal");
        int fishStart = source.indexOf("public boolean canAddFish");
        int animalRemainingStart = source.indexOf("public int remainingAnimalCapacity");
        int fishRemainingStart = source.indexOf("public int remainingFishCapacity");

        String canAddAnimal = source.substring(animalStart, fishStart);
        String canAddFish = source.substring(fishStart, animalRemainingStart);
        String remainingAnimal = source.substring(animalRemainingStart, fishRemainingStart);
        String remainingFish = source.substring(fishRemainingStart);

        assertTrue(canAddAnimal.contains("settings.animalCapacity()"));
        assertFalse(canAddAnimal.contains("settings.fishCapacity()"));
        assertTrue(canAddFish.contains("settings.fishCapacity()"));
        assertFalse(canAddFish.contains("settings.animalCapacity()"));
        assertTrue(remainingAnimal.contains("settings.animalCapacity()"));
        assertFalse(remainingAnimal.contains("settings.fishCapacity()"));
        assertTrue(remainingFish.contains("settings.fishCapacity()"));
        assertFalse(remainingFish.contains("settings.animalCapacity()"));
    }
}
