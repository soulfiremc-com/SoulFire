import type { MessageInitShape } from "@bufbuild/protobuf";
import type { ItemSelectorSchema } from "./generated/soulfire/inventory_pb.js";

export type ItemSelectorInput = string | MessageInitShape<typeof ItemSelectorSchema>;

/** An item ID or a #tag, with the Minecraft namespace as the default. */
export function itemSelector(
  input: ItemSelectorInput,
): MessageInitShape<typeof ItemSelectorSchema> {
  if (typeof input !== "string") return input;
  return input.startsWith("#")
    ? { tags: [resourceId(input.slice(1))] }
    : { itemIds: [resourceId(input)] };
}

export function blockSelectors(input: string | readonly string[]): {
  blockIds: string[];
  tags: string[];
} {
  const blockIds: string[] = [];
  const tags: string[] = [];
  for (const value of typeof input === "string" ? [input] : input) {
    if (value.startsWith("#")) tags.push(resourceId(value.slice(1)));
    else blockIds.push(resourceId(value));
  }
  return { blockIds, tags };
}

function resourceId(value: string): string {
  const id = value.includes(":") ? value : `minecraft:${value}`;
  if (!/^[a-z0-9_.-]+:[a-z0-9/._-]+$/u.test(id))
    throw new TypeError(`Invalid Minecraft resource ID: ${value}`);
  return id;
}
