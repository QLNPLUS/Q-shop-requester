# Changelog

Release notes are grouped by Minecraft loader when a release supports both Forge and NeoForge.

## [1.2.1] - 2026-08-31

### NeoForge 1.21.1

### Added

- Added a shaped recipe using two iron ingots and one barrel to craft a requester.

### Fixed

- Matched requester block hardness, explosion resistance, sound, and tool requirements to the vanilla barrel.

## [1.2.0] - 2026-08-28

### NeoForge 1.21.1

### Added

- Added requester ownership information to the settings page with the owner avatar, name, and online status.
- Added a Claim button that transfers requester ownership to the player using the container.
- Added server-side ownership synchronization for all players viewing the same requester box.
- Added owner controls to the requester layout debug editor.
- Bumped the network protocol for the new owner synchronization payload.

### Changed

- Kept requester ownership changes independent from the shop entry selection list.
- Preserved requester settings and UUID-based shop, tab, and entry targets when ownership is claimed.

### Forge 1.20.1

- No Forge 1.20.1-specific change was included in this NeoForge workspace entry.

## [1.1.1] - 2026-08-27

### Changed

- Switched requester targets from numeric shop, tab, and entry indexes to stable shop, tab, and entry UUIDs.
- Added automatic migration for requester blocks created before version 1.1.1.
- Made shop reordering and entry reordering safe for existing requester targets.
- Updated the shop selection and network synchronization flow to preserve UUID-based targets.

## [1.1.0] - 2026-08-26

### Added

- Changed requester target selection to show shops first and open the QShop interface for entry selection.
- Added support for BUY, SELL, BARTER, and COMMAND entries in automatic trading.
- Added online and offline handling for supplied items, currency balances, commands, and purchase limits.
- Updated the requester integration to build against QShop 1.2.2.

## [1.0.1] - 2026-08-23

### Added

- Added complete requester block properties, including wood map color, wood sounds, explosion resistance, and axe mining support.
- Added the requester block to the `mineable/axe` block tag.
- Added an explicit block particle texture for the requester block.
- Added EMI compatibility for the settings screen to prevent hidden EMI item areas from receiving hover and tooltip interactions.
- Added a CurseForge link for the QShop dependency to the project description.
- Added the `qshop_requester-common.toml` configuration file with the F8 layout debug mode disabled by default.

### Fixed

- Preserved purchased and supplied inventory contents as block drops when the requester is removed.
- Kept automatic trading limited to one shop transaction unit per configured interval.

## [1.0.0]

- Initial release of QShop Requester.
