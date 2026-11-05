import { BotActionStatus, type BotActionResult } from "./generated/soulfire/bot_live_pb.js";

/**
 * Thrown when a bot action ends in a status other than COMPLETED. `result`
 * holds the status and the error.
 */
export class SoulFireActionError extends Error {
  public readonly _tag = "SoulFireActionError";
  public constructor(public readonly result: BotActionResult) {
    super(result.error ?? `Bot action ${result.actionId} did not complete`);
    this.name = "SoulFireActionError";
  }
}

/**
 * Returns `result` if the action completed, and throws `SoulFireActionError`
 * otherwise.
 */
export function requireCompletedAction(result: BotActionResult | undefined): BotActionResult {
  if (result === undefined) {
    throw new Error("Soulfire did not return a bot action result");
  }
  if (result.status !== BotActionStatus.COMPLETED) {
    throw new SoulFireActionError(result);
  }
  return result;
}
