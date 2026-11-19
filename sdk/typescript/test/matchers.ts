import { expect } from "vitest";

// Vitest types its asymmetric matchers as `any` so they fit anywhere. This
// wrapper returns `unknown` instead, which every assertion accepts, so `any`
// does not leak into the expected values.

export const objectContaining = (expected: object): unknown => expect.objectContaining(expected);
