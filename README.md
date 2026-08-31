# Catalog

A free and open source plugin manager for Paper and Velocity, built exclusively on the
[Modrinth API](https://docs.modrinth.com/).

Catalog identifies every jar in your `plugins/` folder by its SHA-512 hash, so it always knows
exactly which project and which version you are running. It never compares version strings, never
scrapes a website, and never guesses.

## Why Modrinth only

Managers that abstract several stores at once have to work around the fact that most of those
stores have no API. That means scraping "recently updated" feeds and comparing version strings,
which produces update notifications for updates that do not exist.

Modrinth exposes a real API with hash lookups. Catalog asks it a direct question — *what is this
exact file, and is there a newer one?* — and gets an authoritative answer in a single request for
the entire server.

## Principles

- **Catalog never touches a running plugin.** No hot-loading, no hot-unloading, no reflection into
  the plugin manager. Changes are applied at restart through Bukkit's native update folder.
- **Never compare version strings.** Identity is the version id, ordering is the publish date,
  integrity is the SHA-512 hash.
- **What Catalog does not recognise, Catalog does not touch.**
- **No network calls on the main thread.**
- **No paid features.**

## Status

Under initial development. Not yet released.

## Modules

| Module | Contents |
|---|---|
| `core` | Modrinth client, jar index, tracking state, update and dependency logic. No platform dependencies. |
| `paper` | Paper, Purpur and Folia frontend, including the inventory GUI. |
| `velocity` | Velocity frontend, with a chat-based interface. |

## Building

```
./gradlew build
```

The jars are written to `paper/build/libs/` and `velocity/build/libs/`.

## Releasing

Releases are published to Modrinth by CI when an annotated `v*` tag is pushed. The Modrinth
project id in `.github/workflows/build.yml` still needs to be filled in.

## License

MIT
