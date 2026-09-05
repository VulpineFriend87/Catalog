# Catalog

The Modrinth-native plugin manager for Paper servers.

Catalog analyses your plugins folder once the server boots, figuring out which Modrinth plugin each .jar belongs to. Then you will get your updates in chat and be able to install new plugins with a click. Every plugin that Catalog does not recognize is left untouched.

## Installation

You can search Modrinth without leaving the game. `/catalog search <query>` gives you the plugins that run on your server, and clicking a result opens its page with the description, author, downloads and what the plugin needs to work.

Installing is one click from there. The .jar goes into your plugins folder and the plugin starts working after the next restart.

Only builds made for your exact Minecraft version are offered. Purpur and Folia builds are recognized separately from plain Paper ones, so if a plugin ships both a Paper and a Purpur build, the Purpur one gets installed on a Purpur server.

## Updates

Nothing is ever swapped while the plugin runs. Catalog downloads the new build and gives it to the server, which installs it automatically once the next restart happens, before plugins load.

You can let Catalog handle that process. Once you enable automatic updates for a trusted plugin, it will stay up-to-date. Catalog waits a couple of hours after a build comes out before installing it. If the author notices a problem and puts out a fix in that time, you get the fixed build and never install the broken one. The pause time and auto-update switch are set per each plugin, so you can choose which plugins you want updated immediately and which ones can wait.

Each downloaded file is being validated before being added to your plugin folder: the right file is expected, the right size is expected and it is being made for the Java version you can use on your server. Anything that does not pass this check will be deleted instead of being placed into your plugin folder.

## Uninstallation

Uninstalled plugins are moved to a separate "trash" folder instead of being deleted. Removing one gives you an Undo, and `/catalog trash` lists everything you have taken out so you can put any of it back. Removals are deleted after 30 days, which you can change or turn off in the config.

## Commands

| Command | |
| --- | --- |
| `/catalog list` | All plugins managed by Catalog |
| `/catalog info <plugin>` | All available information about any plugin, whether installed or not |
| `/catalog search <query>` | Searches Modrinth for plugins compatible with your server |
| `/catalog versions <plugin>` | Newest release, beta and alpha |
| `/catalog install <slug>` | Install a plugin |
| `/catalog update <plugin\|all>` | Download updates, apply them on next restart |
| `/catalog uninstall <plugin>` | Move a plugin to trash |
| `/catalog trash` | What you have removed |
| `/catalog trash restore <plugin>` | Put a removed plugin back |
| `/catalog trash delete <plugin\|all>` | Delete a removal permanently |
| `/catalog settings <plugin>` | Set channels, enable auto-updates, set holds |
| `/catalog reload` | Reload config |

Everything is clickable, so you rarely need to type any command. `/catalog help` lists all commands.

The settings screen sets everything below by clicking, but these also work as commands, which is what you need from the console.

| Command | |
| --- | --- |
| `/catalog channel <plugin> <channel>` | Follow release, beta or alpha |
| `/catalog auto <plugin> <on\|off>` | Update this plugin without asking |
| `/catalog soak <plugin> <window>` | How long to wait before an automatic update |
| `/catalog hold <plugin>` | Keep the current version and stop offering updates |
| `/catalog unhold <plugin>` | Allow updates again |

## Requirements

Paper 1.18.2 and newer, Purpur and Folia included. Java 17 and newer. Plugins should be on Modrinth in order to be managed by Catalog.
