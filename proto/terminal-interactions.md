# Terminal interaction protocol, version 1

This reference defines the experimental SoulFire extension for server and client developers. The extension preserves Adventure hover and click events in personal command output. It does not change standard ANSI SGR styling.

## Wire format

A span starts with `ESC ] 777 ; soulfire ; 1 ; PAYLOAD ESC \\` and ends with `ESC ] 777 ; soulfire ; 1 ; - ESC \\`. Spaces in this notation separate fields; they are not wire bytes. `ESC` is byte `0x1b`. The final backslash is one byte. OSC 777 is a private extension namespace, not an assigned terminal standard.

`PAYLOAD` is unpadded base64url of UTF-8 JSON:

```json
{"hover":"Show available commands","click":{"action":"run_command","value":"help"}}
```

Both properties are optional. `hover` contains plain text from `show_text`. `click.action` supports `run_command`, `suggest_command`, `copy_to_clipboard`, and `open_url`. Other Adventure events are omitted. URLs must be absolute HTTP(S), with a host and without credentials. Action values must be nonempty and contain no C0 or C1 control characters. This version does not support multiple commands or formatted hover components.

Each marker applies to subsequent visible text until the next marker or message end. Markers replace metadata; they do not form a stack. The server resolves component style inheritance and closes each flattened text span. Literal control sequences in component and hover text are removed before encoding. Normal terminals ignore the OSC string and show the styled text.

## Limits and invalid input

The base64url payload is limited to 16,384 characters. Hover text and action values are limited to 4,096 UTF-16 code units each. A message can start at most 64 interactive spans. Text beyond these limits remains visible without interactions.

Clients remove all unknown controls, unsupported versions, malformed metadata, raw OSC 8 links, and clipboard OSC 52 requests. An invalid private marker ends the active span. An unfinished control string is consumed through the end of the message. Metadata never carries into another log entry.

## Trust and activation

The `LogString.personal` and `LogString.terminal_interactions` fields must both be true before a client interprets private metadata. The server sets the latter only for the component overload of `StateHolder.sendPersonalMessage`. The string overload, standard logs, Minecraft chat, and history never grant trust. Callers must not forward untrusted components with click events into that overload. The serializer prevents literal escape injection; it cannot decide whether an Adventure event is trusted.

Hovering only shows text. Run and suggest actions require deliberate activation and a mounted command input with permission to execute commands. Run actions use the same authenticated command RPC and current global, instance, or bot scope as that input. Metadata cannot select another scope. Suggest actions insert a draft without executing it. Copy and URL actions also require activation. Selection drags and modified clicks do not activate actions.

Older clients strip private markers. New clients treat output from older servers as ordinary ANSI text because the new trust field defaults to false.
