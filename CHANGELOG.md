# Changelog

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
