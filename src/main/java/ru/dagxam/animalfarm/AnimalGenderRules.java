package ru.dagxam.animalfarm;

/** Чистые правила совместимости пола без Bukkit-зависимостей. */
public final class AnimalGenderRules {
    private AnimalGenderRules() {
    }

    public static boolean canBreed(AnimalGender first, AnimalGender second, boolean requireMaleAndFemale) {
        if (!requireMaleAndFemale) return true;
        if (first == null || second == null) return false;
        return first != second;
    }

    public static boolean canGiveMilk(AnimalGender gender, boolean femalesOnly) {
        if (!femalesOnly) return true;
        return gender == AnimalGender.FEMALE;
    }
}
