import type { Any } from "@bufbuild/protobuf/wkt";

/** Returns the packed input of a captured task request, or throws when it is missing. */
export function taskInput(request: { readonly input?: Any | undefined } | undefined): Any {
  if (request?.input === undefined) {
    throw new Error("The captured task request has no input");
  }
  return request.input;
}

/** Returns `message`, or throws when a packed task input did not match its schema. */
export function unpacked<T>(message: T | undefined): T {
  if (message === undefined) {
    throw new Error("The task input does not match the expected schema");
  }
  return message;
}
