import { expect } from "vitest";

// Vitest types its asymmetric matchers as `any` so they fit anywhere. These
// wrappers return `unknown` instead, which every assertion accepts, so `any`
// does not leak into the expected values.

export const arrayContaining = (expected: unknown[]): unknown => expect.arrayContaining(expected);

export const closeTo = (expected: number, precision?: number): unknown =>
  expect.closeTo(expected, precision);

export const objectContaining = (expected: object): unknown => expect.objectContaining(expected);

export const stringContaining = (expected: string): unknown => expect.stringContaining(expected);

export const stringMatching = (expected: string | RegExp): unknown =>
  expect.stringMatching(expected);

export const anyInstanceOf = (type: abstract new (...args: never[]) => unknown): unknown =>
  expect.any(type);
