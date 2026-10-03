/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.task;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class BreedTaskProviderTest {
  private static final UUID COW = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_COW = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private final LocalPlayer player = mock(LocalPlayer.class);

  private Animal animal(UUID uuid, double distanceToPlayerSqr) {
    var animal = mock(Animal.class);
    when(animal.getUUID()).thenReturn(uuid);
    when(animal.distanceToSqr(player)).thenReturn(distanceToPlayerSqr);
    return animal;
  }

  private List<Animal> searchOrder(
    BreedTaskProvider.RefusedAnimals refused,
    long gameTime,
    Animal... animals
  ) {
    return Stream.of(animals)
      .sorted(BreedTaskProvider.searchOrder(refused, gameTime, player))
      .toList();
  }

  @Test
  void triesTheNearestAnimalFirst() {
    var near = animal(COW, 4);
    var far = animal(OTHER_COW, 49);

    assertEquals(
      List.of(near, far),
      searchOrder(new BreedTaskProvider.RefusedAnimals(), 1_000, far, near)
    );
  }

  @Test
  void triesARefusedAnimalAfterTheOthers() {
    var near = animal(COW, 4);
    var far = animal(OTHER_COW, 49);
    var refused = new BreedTaskProvider.RefusedAnimals();
    refused.add(COW, 1_000);

    assertEquals(List.of(far, near), searchOrder(refused, 1_000, near, far));
  }

  @Test
  void triesARefusedAnimalByDistanceAgainOnceTheCooldownIsOver() {
    var near = animal(COW, 4);
    var far = animal(OTHER_COW, 49);
    var refused = new BreedTaskProvider.RefusedAnimals();
    refused.add(COW, 1_000);

    assertEquals(List.of(near, far), searchOrder(refused, 7_000, far, near));
  }

  @Test
  void remembersARefusalForTheBreedingCooldown() {
    var refused = new BreedTaskProvider.RefusedAnimals();
    refused.add(COW, 1_000);

    assertTrue(refused.contains(COW, 1_000));
    assertTrue(refused.contains(COW, 6_999));
    assertFalse(refused.contains(OTHER_COW, 1_000));
  }

  @Test
  void forgetsARefusalOnceTheCooldownIsOver() {
    var refused = new BreedTaskProvider.RefusedAnimals();
    refused.add(COW, 1_000);

    assertFalse(refused.contains(COW, 7_000));
  }

  @Test
  void keepsEarlierRefusalsWhenAnotherAnimalIsRefused() {
    var refused = new BreedTaskProvider.RefusedAnimals();
    refused.add(COW, 1_000);
    refused.add(OTHER_COW, 6_999);

    assertTrue(refused.contains(COW, 6_999));
    assertTrue(refused.contains(OTHER_COW, 12_998));
  }
}
