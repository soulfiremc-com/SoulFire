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

import com.mojang.blaze3d.platform.InputConstants;

/// Converts the POV protocol's GLFW codes into Minecraft's SDL input values.
final class PovInputCodes {
  private PovInputCodes() {}

  static int scancode(int key) {
    if (key >= 65 && key <= 90) return key - 65 + InputConstants.KEY_A;
    if (key >= 49 && key <= 57) return key - 49 + InputConstants.KEY_1;
    if (key == 48) return InputConstants.KEY_0;
    if (key >= 290 && key <= 301) return key - 290 + InputConstants.KEY_F1;
    if (key >= 302 && key <= 313) return key - 302 + InputConstants.KEY_F13;
    if (key >= 321 && key <= 329) return key - 321 + InputConstants.KEY_NUMPAD1;
    return switch (key) {
      case 32 -> InputConstants.KEY_SPACE;
      case 39 -> InputConstants.KEY_APOSTROPHE;
      case 44 -> InputConstants.KEY_COMMA;
      case 45 -> InputConstants.KEY_MINUS;
      case 46 -> InputConstants.KEY_PERIOD;
      case 47 -> InputConstants.KEY_SLASH;
      case 59 -> InputConstants.KEY_SEMICOLON;
      case 61 -> InputConstants.KEY_EQUALS;
      case 91 -> InputConstants.KEY_LBRACKET;
      case 92 -> InputConstants.KEY_BACKSLASH;
      case 93 -> InputConstants.KEY_RBRACKET;
      case 96 -> InputConstants.KEY_GRAVE;
      case 161 -> 100;
      case 162 -> 50;
      case 256 -> InputConstants.KEY_ESCAPE;
      case 257 -> InputConstants.KEY_RETURN;
      case 258 -> InputConstants.KEY_TAB;
      case 259 -> InputConstants.KEY_BACKSPACE;
      case 260 -> InputConstants.KEY_INSERT;
      case 261 -> InputConstants.KEY_DELETE;
      case 262 -> InputConstants.KEY_RIGHT;
      case 263 -> InputConstants.KEY_LEFT;
      case 264 -> InputConstants.KEY_DOWN;
      case 265 -> InputConstants.KEY_UP;
      case 266 -> InputConstants.KEY_PAGEUP;
      case 267 -> InputConstants.KEY_PAGEDOWN;
      case 268 -> InputConstants.KEY_HOME;
      case 269 -> InputConstants.KEY_END;
      case 280 -> InputConstants.KEY_CAPSLOCK;
      case 281 -> InputConstants.KEY_SCROLLLOCK;
      case 282 -> InputConstants.KEY_NUMLOCK;
      case 283 -> InputConstants.KEY_PRINTSCREEN;
      case 284 -> InputConstants.KEY_PAUSE;
      case 320 -> InputConstants.KEY_NUMPAD0;
      case 330 -> 99;
      case 331 -> 84;
      case 332 -> InputConstants.KEY_MULTIPLY;
      case 333 -> 86;
      case 334 -> InputConstants.KEY_ADD;
      case 335 -> InputConstants.KEY_NUMPADENTER;
      case 336 -> InputConstants.KEY_NUMPADEQUALS;
      case 340 -> InputConstants.KEY_LSHIFT;
      case 341 -> InputConstants.KEY_LCONTROL;
      case 342 -> InputConstants.KEY_LALT;
      case 343 -> InputConstants.KEY_LGUI;
      case 344 -> InputConstants.KEY_RSHIFT;
      case 345 -> InputConstants.KEY_RCONTROL;
      case 346 -> InputConstants.KEY_RALT;
      case 347 -> InputConstants.KEY_RGUI;
      case 348 -> 118;
      default -> 0;
    };
  }

  static int keycode(int key) {
    if (key >= 65 && key <= 90) return key + 32;
    if (key >= 32 && key <= 126) return key;
    return switch (key) {
      case 256 -> 27;
      case 257 -> 13;
      case 258 -> 9;
      case 259 -> 8;
      case 261 -> 127;
      default -> scancode(key) == 0 ? 0 : scancode(key) | 1 << 30;
    };
  }

  static int modifiers(int modifiers) {
    return ((modifiers & 1) != 0 ? InputConstants.MOD_SHIFT : 0)
      | ((modifiers & 2) != 0 ? InputConstants.MOD_CONTROL : 0)
      | ((modifiers & 4) != 0 ? InputConstants.MOD_ALT : 0)
      | ((modifiers & 8) != 0 ? InputConstants.MOD_SUPER : 0)
      | ((modifiers & 16) != 0 ? InputConstants.MOD_CAPS_LOCK : 0)
      | ((modifiers & 32) != 0 ? InputConstants.MOD_NUM_LOCK : 0);
  }

  static int mouseButton(int button) {
    return switch (button) {
      case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
      case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
      case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
      default -> button + 1;
    };
  }
}
