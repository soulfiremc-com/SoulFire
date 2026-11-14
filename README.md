<img align="right" src="https://github.com/soulfiremc-com/SoulFire/blob/main/mod/src/main/resources/icons/icon.png?raw=true" height="150" width="150">

[![discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/social/discord-singular_vector.svg)](https://discord.gg/vHgRd6YZmH) [![kofi](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/donate/kofi-singular_vector.svg)](https://ko-fi.com/alexprogrammerde)

# Soulfire

Soulfire is the display name. Existing repository names, Java types, SDK exports, package coordinates, protocol identifiers, and download filenames retain their compatible spelling.

Advanced Minecraft Bot Tool. Deploy automated bots for server testing, automation, and development.

This repository only contains the CLI and server implementation. The official GUI client is in [another repository](https://github.com/soulfiremc-com/SoulFireClient).

> [!WARNING]
> Use this tool only on servers that you own or have permission to test. Make sure that your hosting provider allows
> automated bot testing. You are responsible for unauthorized use. The Soulfire developers are not responsible for your actions.

---

<img align="right" src="https://enderdash.com/logo.png" height="150" width="150">

### ✨ Are you looking for an advanced administration panel for your server?

EnderDash allows you to manage your existing Minecraft servers using an advanced dashboard by installing a single plugin, batteries included. Run commands (with tab completion), read logs, manage files, invite your whole team, manage players, Ocelot (AI Assistant), works with your existing Infrastructure/Panels and much more.

Check it out: [https://enderdash.com](https://enderdash.com/?utm_source=github&utm_medium=readme&utm_campaign=soulfire)

---

## 🚀 Features

* GUI (Multiple themes) & CLI
* Configurable options for every session like the number of bots, join delay and more
* Load and save profiles for quick access
* Support for online and offline mode servers
* Supports [almost every Minecraft version](#-version-support)
* Use `Microsoft` (Credentials & Device Code supported) and `Offline` accounts for both Java Edition and Bedrock Edition
* Use `HTTP`, `SOCKS4` and `SOCKS5` proxies
* Multiple [built-in plugins](#-plugins) like `AutoRespawn`, `AutoJump`, `ClientSettings` and more
* Console command support
* A* Pathfinding (Diagonal moves, parkour, mining blocks, placing blocks)

## 🖥 Installation

> [!TIP]
> Want to check out how Soulfire looks before installing it? Take a look at the official [demo page](https://demo.soulfiremc.com).

Follow the [installation guide](https://soulfiremc.com/docs/installation) to install Soulfire.

The server bundles a headless Vulkan runtime and Mesa lavapipe for CPU rendering. No system Vulkan packages or display server are required.
Installed compatible GPU drivers provide hardware acceleration. See [native runtime packaging](docs/vulkan-runtime.md) for supported platforms and build instructions.
The Docker image includes lavapipe. On Debian or Ubuntu, install `libvulkan1` and `mesa-vulkan-drivers` when running without a GPU driver.
POV captures and inventory images render on demand without a native window. OpenGL is disabled.
See [renderer validation](docs/lavapipe-test.md) for the manual comparison tests.

## 🍿 Version support

You can find an up-to-date list of supported versions in
the [documentation](https://soulfiremc.com/docs/usage/versions).

## ⌨ Commands

For a list of all available commands, look at the [documentation](https://soulfiremc.com/docs/usage/commands)
or run `help` in the GUI or CLI.

## 📻 Plugins

You can read about the Soulfire plugins in the [documentation](https://soulfiremc.com/docs/usage/plugins).

## 🗃 Import accounts and proxies

You can read about how to import accounts in the [account documentation](https://soulfiremc.com/docs/usage/accounts) and
how to import proxies in the [proxy documentation](https://soulfiremc.com/docs/usage/proxies).

## 🤿 Looking for proxies?

There are many websites that offer free proxies, but be careful as many of them are not reliable or secure.

Our recommended proxy providers are [LegionProxy](https://legionproxy.io/?utm_source=github&utm_campaign=soulfire) and
[Proxy-Seller](https://proxy-seller.com/?partner=GRJY71PA3XWPPP&utm_source=get-proxies&utm_campaign=soulfire), who sponsor Soulfire.

For a full list of recommended providers, check out our [Get Proxies](https://soulfiremc.com/get-proxies) page.

## 💻 Command Line Usage

If you want to use the CLI of Soulfire, please refer to the [CLI Mode Guide](https://soulfiremc.com/docs/guides/cli-mode).

## 🧵 Demo

Soulfire has a built-in GUI for easy usage. Try a Soulfire demo yourself at the [demo page](https://demo.soulfiremc.com).

https://github.com/user-attachments/assets/6244d54c-a1e5-4467-a705-c929b9de2b57

## ✨ Nightly builds

You can download the latest development version of Soulfire
through [nightly.link](https://nightly.link/soulfiremc-com/SoulFire/workflows/build/main).

## 🔧 Build from source

1. Install a JDK 25 and clone this repository.
2. Prepare the packaged native runtimes using the [native runtime build instructions](docs/vulkan-runtime.md).
3. Run `./gradlew build` in the project directory. For a local build, select the prepared platforms with `-PvulkanPlatforms=<platform>`.
4. Get the JAR from `client-launcher/build/libs` or `dedicated-launcher/build/libs`.

The default build requires all six native runtime platforms.
For environment setup, module locations, SDK generation, and tests, read the [contribution guide](CONTRIBUTING.md).

## 👨‍💻 Developer API

Soulfire offers a Developer API to create your own plugins using the plugin API and mixins.
Soulfire can include breaking changes. Pin your plugin to a Soulfire version, or update it when Soulfire changes.

To learn more about the Developer API, take a look at the
official [plugin example](https://github.com/soulfiremc-com/SoulFirePluginExample).

Soulfire also includes [TypeScript and Python SDKs](sdk/README.md) for
connecting over gRPC-Web, streaming bot events, issuing current per-bot
actions, and provisioning a local dedicated server.

## 🌈 Community

Feel free to join our Discord community server:

[![Discord Banner](https://discord.com/api/guilds/739784741124833301/widget.png?style=banner2)](https://discord.gg/vHgRd6YZmH)

For setup and usage questions, read the [support resources](SUPPORT.md).
Report bugs and propose features through the [issue forms](https://github.com/soulfiremc-com/SoulFire/issues/new/choose).
Pull requests are welcome. Read the [contribution guide](CONTRIBUTING.md) and [code of conduct](CODE_OF_CONDUCT.md) before you start.
Report vulnerabilities privately through the [security policy](SECURITY.md).

## 🏅 Sponsors

<table>
 <tbody>
  <tr>
   <td align="center"><img alt="[SignPath]" src="https://avatars.githubusercontent.com/u/34448643" height="30"/></td>
   <td>Free code signing on Windows provided by <a href="https://signpath.io/?utm_source=foundation&utm_medium=github&utm_campaign=soulfire">SignPath.io</a>, certificate by <a href="https://signpath.org/?utm_source=foundation&utm_medium=github&utm_campaign=soulfire">SignPath Foundation</a></td>
  </tr>
 </tbody>
</table>

## 🌟 Star History

<a href="https://star-history.com/#soulfiremc-com/SoulFire&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=soulfiremc-com/SoulFire&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=soulfiremc-com/SoulFire&type=Date" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=soulfiremc-com/SoulFire&type=Date" />
  </picture>
</a>
