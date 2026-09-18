<a href="https://modrinth.com/plugin/catalog"><img alt="Available on Modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg"></a>
<img alt="Works on Paper 1.18.2+" height="56" src="https://u.vulpine.top/u/AIB6AM.svg">
<img alt="Requires Java 17+" height="56" src="https://u.vulpine.top/u/ggP6Ta.svg">

> **Note**: as of right now, the project is awaiting review on Modrinth, so it won't be available for download unless you compile it yourself.

Catalog analyses your plugins folder once the server boots, figuring out which Modrinth plugin each `.jar` belongs to. Every jar is identified by its hash, so a renamed file is still recognized and nothing has to be mapped by hand. Then you will get your updates in chat and be able to search, install and remove plugins with a click. Every plugin that Catalog does not recognize is left untouched.

## Requirements

Paper 1.18.2 and newer, Purpur and Folia included. Java 17 and newer. If either one is missing, Catalog disables itself during startup and prints the reason in the console. Folia is supported.

Plugins have to be published on Modrinth to be managed. Custom jars and plugins from other marketplaces keep working as usual, Catalog only ignores them. If you install a plugin that is available on modrinth it will be recognized.

## Setup

Download the jar from [Modrinth](https://modrinth.com/plugin/catalog) and drop it into your plugins folder. On the first start Catalog scans what you already have and matches it against Modrinth. `/catalog list` will show all of the recognized plugins and their updates.

## Installation

You can search Modrinth without leaving the game. `/catalog search <query>` gives you the plugins that run on your server, and clicking a result opens its page with the description, author, downloads and what the plugin needs to work.

Installing is one click from there. The `.jar` goes into your plugins folder and the plugin starts working after the next restart. If the build needs a plugin you do not have, Catalog says which one and offers to bring it along.

Only builds made for your exact Minecraft version are offered. Purpur and Folia builds are recognized separately from plain Paper ones, so if a plugin ships both a Paper and a Purpur build, the Purpur one gets installed on a Purpur server.

`/catalog install <plugin> <version>` puts a plugin on any other build, which is how you roll back. Replacing a jar that currently works asks for confirmation first.

## Updates

Catalog looks for updates when the server starts and every three hours after that. `/catalog list` shows what is out of date, and `/catalog update <plugin>` or `/catalog update all` fetches the new builds.

Nothing is ever swapped while the plugin runs. Catalog downloads the new build and gives it to the server, which installs it automatically once the next restart happens, before plugins load. `/catalog cancel <plugin>` drops a staged update.

You can let Catalog handle that process. Once you enable automatic updates for a trusted plugin, it will stay up-to-date. Catalog waits a couple of hours after a build comes out before installing it. If the author notices a problem and puts out a fix in that time, you get the fixed build and never install the broken one. The pause time, the channel a plugin follows and the auto-update switch are set per each plugin, so you can choose which plugins you want updated immediately and which ones can wait.

Each downloaded file is being validated before being added to your plugin folder: the right file is expected, the right size is expected and it is being made for the Java version you can use on your server. Anything that does not pass this check will be deleted instead of being placed into your plugin folder.

## Uninstallation

Uninstalled plugins are moved to a separate "trash" folder instead of being deleted. Removing one gives you an Undo, and `/catalog trash` lists everything you have taken out so you can put any of it back. Taking out a plugin that another one depends on asks first. Removals are deleted after 30 days, which you can change or turn off in the config.

## History

`/catalog history` lists what Catalog has done to your server: every install, update, removal and restore, when it happened and who asked for it.

## Commands

Everything is clickable, so you rarely need to type any command. `/catalog help` lists all commands. The command also answers to `/ctlg`, `/cata`, `/ctl` and `/clg`.

| Command | | Permission |
| --- | --- | --- |
| `/catalog` | Version and credits | `catalog.command.about` |
| `/catalog help` | All commands | `catalog.command.help` |
| `/catalog list` | All plugins managed by Catalog, and what needs updating | `catalog.command.list` |
| `/catalog info <plugin>` | All available information about any plugin, whether installed or not | `catalog.command.info` |
| `/catalog search <query>` | Searches Modrinth for plugins compatible with your server | `catalog.command.search` |
| `/catalog versions <plugin>` | Newest release, beta and alpha | `catalog.command.info` |
| `/catalog dependencies <plugin>` | What a plugin declares it needs | `catalog.command.info` |
| `/catalog history` | What Catalog has done, and who asked | `catalog.command.list` |
| `/catalog install <slug> [version]` | Install a plugin, optionally a specific build | `catalog.command.install` |
| `/catalog update <plugin\|all>` | Download updates, apply them on next restart | `catalog.command.update` |
| `/catalog cancel <plugin>` | Drop a staged update | `catalog.command.update` |
| `/catalog uninstall <plugin>` | Move a plugin to trash | `catalog.command.uninstall` |
| `/catalog trash` | What you have removed | `catalog.command.trash` |
| `/catalog trash restore <plugin>` | Put a removed plugin back | `catalog.command.trash` |
| `/catalog trash delete <plugin\|all>` | Delete a removal permanently | `catalog.command.trash` |
| `/catalog settings <plugin>` | Set channels, enable auto-updates, set holds | `catalog.command.settings` |
| `/catalog reload` | Reload config | `catalog.command.reload` |

The settings screen sets everything below by clicking, but these also work as commands, which is what you need from the console.

| Command | | Permission |
| --- | --- | --- |
| `/catalog channel <plugin> <channel>` | Follow release, beta or alpha | `catalog.command.channel` |
| `/catalog auto <plugin> <on\|off>` | Update this plugin without asking | `catalog.command.settings` |
| `/catalog soak <plugin> <window>` | How long to wait before an automatic update, like `30m`, `2h` or `default` | `catalog.command.settings` |
| `/catalog hold <plugin>` | Keep the current version and stop offering updates | `catalog.command.hold` |
| `/catalog unhold <plugin>` | Allow updates again | `catalog.command.hold` |

## Permissions

Permissions are listed above next to each action/command.

| Node | |
| --- | --- |
| `catalog.admin` | Everything |
| `catalog.*` | Everything |
| `catalog.command.<name>` | A single command/action |

## Configuration

`plugins/Catalog/config.yml` is short and commented. What most people change is the defaults applied to newly tracked plugins, meaning their release channel, whether they update automatically and how long to wait before doing that, then how often updates are checked and how long the trash keeps removals. A Modrinth token is optional: it raises the request limit and allows private projects to be read.

## Questions

**Does it reload or hot-swap plugins?**
No. Catalog does not touch plugins while the server is running, which is what prevents classloader leaks and locked files. Builds are staged and the server applies them at the next restart, before the plugins load.

**What about plugins that are not on Modrinth?**
They are ignored. Catalog never does anything to them.

**Can it install something my server cannot run?**
Only with `allow_incompatible_installs` turned on in the config. Otherwise builds are filtered by your Minecraft version and server platform, and every download is checked against the Java version your server runs.

**Does it phone home?**
It talks to the Modrinth API, and reports anonymous server statistics to [bStats](https://bstats.org), which you can turn off in the bStats config.

Made by [Vulpine](https://vulpine.top)
