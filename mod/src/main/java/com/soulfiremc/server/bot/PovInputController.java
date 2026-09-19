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
package com.soulfiremc.server.bot;

import com.soulfiremc.grpc.generated.PovInputEvent;
import com.soulfiremc.mod.access.IMouseHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.HashSet;
import java.util.Set;

/// Game-thread input state for the interactive viewer. Native Minecraft handles all actions and screens.
public final class PovInputController implements ControlTask {
  private final BotConnection bot;
  private final Set<Integer> keys = new HashSet<>();
  private final Set<Integer> buttons = new HashSet<>();
  private boolean active;
  private long lastInput;

  public PovInputController(BotConnection bot) { this.bot = bot; }
  public boolean active() { return active; }
  public boolean keyDown(int key) { return keys.contains(key); }

  public void capture(boolean captured) {
    lastInput = System.nanoTime();
    if (captured == active) return;
    if (captured) {
      bot.botControl().replace(this);
      active = true;
      bot.minecraft().mouseHandler.mouseGrabbed = bot.minecraft().gui.screen() == null;
    } else {
      bot.botControl().cancel(this);
      release();
    }
  }

  public void accept(PovInputEvent event) {
    if (!active) return;
    var minecraft = bot.minecraft();
    var handle = minecraft.getWindow().handle();
    switch (event.getKind()) {
      case KEY -> {
        var key = PovInputCodes.scancode(event.getCode());
        if (key == 0) return;
        if (event.getAction() == 0) keys.remove(key);
        else keys.add(key);
        minecraft.keyboardHandler.keyPress(handle, event.getAction() == 2 ? -1 : event.getAction(),
          new KeyEvent(key, PovInputCodes.keycode(event.getCode()), PovInputCodes.modifiers(event.getModifiers())));
      }
      case CHARACTER -> minecraft.keyboardHandler.charTyped(handle, new CharacterEvent(event.getCode()));
      case BUTTON -> {
        var button = PovInputCodes.mouseButton(event.getCode());
        if (event.getAction() == 0) buttons.remove(button);
        else buttons.add(button);
        minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(button, PovInputCodes.modifiers(event.getModifiers())), event.getAction());
      }
      case MOVE -> {
        if (event.getRelative()) {
          if (minecraft.gui.screen() == null) {
            ((IMouseHandler) minecraft.mouseHandler).soulfire$queueSyntheticMovement(event.getX(), event.getY());
          }
        } else if (minecraft.gui.screen() != null) {
          var window = minecraft.getWindow();
          minecraft.mouseHandler.onMove(handle, event.getX() * window.getScreenWidth(), event.getY() * window.getScreenHeight(), 0, 0);
          minecraft.mouseHandler.handleAccumulatedMovement();
        }
      }
      case SCROLL -> minecraft.mouseHandler.onScroll(handle, event.getX(), event.getY());
      case UNRECOGNIZED -> throw new IllegalArgumentException("Unknown POV input event");
    }
  }

  public void release() {
    var minecraft = bot.minecraft();
    for (var button : Set.copyOf(buttons)) {
      minecraft.mouseHandler.onButton(minecraft.getWindow().handle(), new MouseButtonInfo(button, 0), 0);
    }
    for (var mapping : minecraft.options.keyMappings) mapping.release();
    keys.clear();
    buttons.clear();
    minecraft.mouseHandler.mouseGrabbed = false;
    bot.controlState().resetAll();
    active = false;
  }

  @Override
  public void tick() {
    if (System.nanoTime() - lastInput > 5_000_000_000L) release();
  }
  @Override
  public boolean isDone() { return !active; }
  @Override
  public ControlPriority priority() { return ControlPriority.CRITICAL; }
  @Override
  public void onStopped(ControlStopReason reason, Throwable cause) { release(); }
}
