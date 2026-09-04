# Contributing to Catalog

Catalog is a plugin manager, and as such, moves files on others' servers, and therefore, the bar for changes is purposely a little bit higher than usual.

## Licensing of contributions

By sending us a pull request, you grant permission for your contribution to be used under the license of this project (**GPL-3.0-only**), and **under any other license**, even proprietary, as the project owner sees fit.

If you are not willing to give that permission, please file an issue describing the change instead of sending us a pull request.

## Before you send a pull request

- **File an issue first** for everything other than a bugfix. Catalog has quite an opinionated design.
- **`./gradlew build` has to succeed.**
- **Add a test if the change can be tested without a server.**

## Things that will be rejected

- **Hot load/unload/reload plugins.** Catalog does not touch plugins while server is running. This prevents problems like classloader leak and locked files. This is not negotiable.
- **Comparing version strings.** Identity is `version_id`, ordering is `date_published`, integrity is `sha512`. Version string is something picked by the author of the plugin, and are neither unique nor sorted.
- **Support for stores other than Modrinth.** Catalog is designed to be Modrinth-only.
- **Making network requests from the main thread.**

## Code style

- English, including comments.
- Comments explain **why**, not what.
- Javadoc on public API of `core`.
- Follow the style of the existing code.

## Reporting a bug

Your server version and the Paper fork you use, the Catalog version, and relevant excerpt from `logs/latest.log`. If it's plugin-related, its Modrinth slug.