# Naming migration

Projects hold bots, configuration, scripts, files, access, and metadata.
A project keeps the same identity and configuration while its bots are stopped.
The new display names do not change ownership, permissions, or runtime behavior.

## Existing identifiers

| Display name                    | Existing identifier                                                 |
| ------------------------------- | ------------------------------------------------------------------- |
| Project                         | `Instance`, `instance_id`, `instanceId`, and `instance` SDK methods |
| Project command scope           | The `instance` CLI command and `instance_names` argument            |
| Configuration import and export | Existing profile payloads and file formats                          |
| Auto equip armor                | Plugin and settings namespace `auto-armor`                          |
| Auto equip totem                | Plugin and settings namespace `auto-totem`                          |
| Random jump                     | Plugin and settings namespace `auto-jump`                           |
| Auto chat                       | Plugin and settings namespace `auto-chat-message`                   |
| Auto login and register         | Plugin and settings namespace `auto-register`                       |
| AI chat                         | Plugin and settings namespace `ai-chat-bot`                         |
| Auto attack                     | Plugin and settings namespace `kill-aura`                           |
| Minecraft client settings       | Plugin and settings namespace `client-settings`                     |
| Handshake host override         | Plugin and settings namespace `fake-virtual-host`                   |
| Ping before join                | Plugin and settings namespace `server-list-bypass`                  |
| Player forwarding               | Plugin and settings namespace `forwarding-bypass`                   |
| Cooldown                        | Node type `flow.debounce`                                           |
| On connection created           | Node type `trigger.on_bot_init`                                     |
| On player ready                 | Node type `trigger.on_join`                                         |
| Get movement state              | Node type `data.get_bot_state`                                      |
| Select hotbar slot              | Node type `action.select_slot`                                      |
| Drop cursor item                | Node type `action.drop_item`                                        |
| Log message                     | Node type `action.print`                                            |
| Go to position                  | Node type `action.pathfind_to`                                      |
| Get project runtime variable    | Node type `variable.get_session_instance`                           |
| Get connection variable         | Node type `variable.get_session_bot`                                |
| Get persistent project variable | Node type `variable.get_persistent_instance`                        |
| Get persistent bot variable     | Node type `variable.get_persistent_bot`                             |
| Set project runtime variable    | Node type `variable.set_session_instance`                           |
| Set connection variable         | Node type `variable.set_session_bot`                                |
| Set persistent project variable | Node type `variable.set_persistent_instance`                        |
| Set persistent bot variable     | Node type `variable.set_persistent_bot`                             |

Existing scripts, configuration files, API calls, and command invocations remain valid.
Node metadata includes the previous display names as search keywords.
The migrated app also searches stable plugin IDs as hyphenated, spaced, and compact names, together with plugin descriptions.

## Configuration copies

**Create from configuration** copies the source project's configuration and icon into a new project.
It includes settings, accounts and their credentials, proxies, and project and account persistent metadata.
It does not copy scripts, files, permissions, or history. The new project belongs to the user who creates it.
Exported configuration includes account and proxy credentials and account persistent metadata. Treat the exported file as sensitive.
The app currently omits project-level persistent metadata from configuration exports. Duplication includes it.
Configuration export is not a complete project backup.
Minecraft profile IDs continue to identify Minecraft accounts and are not renamed.

## SDK examples

The SDK methods keep their names:

```typescript
const project = client.instance(projectId);
const bot = project.bot(botId);
```

```python
project = client.instance(project_id)
bot = project.bot(bot_id)
```

Existing examples that name this variable `instance` also work.
No data conversion or dependency upgrade is required to keep using those identifiers.

The [shared naming proposals](https://github.com/soulfiremc-com/brand/tree/main/proposals)
track the product-language decision and coordinated rollout.

## Mixed versions and translations

Raw API errors can still use **Instance** because their identifiers and error text remain compatible.
For example, an existing `instance_id` validation error still describes that field by its API name.
Clients use the labels and node metadata supplied by their connected server. Older servers can show earlier feature names.
Existing graphs keep explicit node labels, including labels chosen by their authors.
The node palette uses the server's current display names and retains previous names in search.

The app updates its `en-US` source strings first. Crowdin translations need review before each localized rollout.
Public SDK types, permission identifiers, routes, generated bindings, and configuration payloads retain their existing spelling.

Temporary connection variables belong to one bot connection. Project runtime variables remain available while bots stop and start.
Project runtime variables reset when the SoulFire server restarts. Persistent variables use the project's or bot account's saved metadata.
