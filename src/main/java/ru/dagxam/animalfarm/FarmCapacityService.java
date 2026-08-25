package ru.dagxam.animalfarm;

/** Единая проверка вместимости для всех механизмов появления новых животных и рыб. */
public final class FarmCapacityService {
    private final FarmSettings settings;

    public FarmCapacityService(FarmSettings settings) {
        this.settings = settings;
    }

    public boolean canAddAnimal(int current, int amount) {
        return amount > 0 && current >= 0 && current + amount <= settings.animalCapacity();
    }

    public boolean canAddFish(int current, int amount) {
        return amount > 0 && current >= 0 && current + amount <= settings.fishCapacity();
    }

    public int remainingAnimalCapacity(int current) {
        return Math.max(0, settings.animalCapacity() - Math.max(0, current));
    }

    public int remainingFishCapacity(int current) {
        return Math.max(0, settings.fishCapacity() - Math.max(0, current));
    }
}
