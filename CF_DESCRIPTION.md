# QShop Requester

## Short Description

Automatically purchase configured QShop trades with currency or barter, even while the owner is offline.

## Full Description

QShop Requester is a QShop addon that reads shop entries and automatically completes selected purchases at a configured interval.

### Features

- Supports currency purchase and barter shop entries.
- Search, filter, and select available QShop entries from the settings screen.
- Displays prices, required items, and received items for selected entries.
- Supports trade intervals in seconds, minutes, hours, and game days.
- Uses two separate 4x3 container areas:
  - Left side: purchased items
  - Right side: player-supplied items
- Hoppers and pipes can insert supplied items from the top and all four sides, and extract purchased items from the bottom.
- Continues automatic trading while the owner is online or offline.
- When the owner is offline, currency and purchase-limit data are read from QShop player data. Offline trading checks currency, item availability, and purchase limits without re-evaluating FTB quests or Stage requirements.
- Supports Action Bar and chat notifications for completed or failed trades.

### Dependencies

- Minecraft Forge 1.20.1
- QShop 1.1.0 or newer, below 2.0

### Installation

1. Install Minecraft Forge 1.20.1.
2. Install QShop.
3. Place the `qshop-requester` jar file in the `mods` folder.

### License

ARR (All Rights Reserved). Redistribution, modification, or inclusion in another project requires permission from the author.
