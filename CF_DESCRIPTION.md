# QShop Requester

## Short Description

Automatically purchase configured QShop trades with currency or barter on Forge 1.20.1 and NeoForge 1.21.1, even while the owner is offline.

## Full Description

QShop Requester is a QShop addon available for Forge 1.20.1 and NeoForge 1.21.1. It reads shop entries and automatically completes selected purchases at a configured interval.

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

- Minecraft Forge 1.20.1 with Forge 47.x, or Minecraft NeoForge 1.21.1 with NeoForge 21.1.x
- [QShop](https://www.curseforge.com/minecraft/mc-mods/q-shop) 1.1.0 or newer, below 2.0

### Installation

1. Install Minecraft Forge 1.20.1 or Minecraft NeoForge 1.21.1.
2. Install [QShop](https://www.curseforge.com/minecraft/mc-mods/q-shop).
3. Place the `qshop-requester` jar file in the `mods` folder.

### License

ARR (All Rights Reserved). Redistribution, modification, or inclusion in another project requires permission from the author.

## 中文描述

### 简介

QShop Requester 是 QShop 的附属 Mod，可以自动读取商店交易项目，并按照设定的时间间隔自动完成购买。即使收购箱所属玩家处于离线状态，也可以继续自动交易。

### 功能

- 支持使用货币购买物品的交易项目。
- 支持以物换物的交易项目。
- 在设置界面读取并选择可用的 QShop 商店交易项目。
- 支持搜索和筛选商店、分页、交易项目和物品名称。
- 显示选中项目的价格、需要的物品以及获得的物品。
- 支持秒、分钟、小时和游戏日四种交易间隔单位。
- 使用两个独立的 4x3 容器区域：
  - 左侧：购入物品容器。
  - 右侧：玩家提供物品容器。
- 漏斗和管道可以从顶部及四个侧面输入玩家提供的物品，并从底部输出购入物品。
- 所属玩家在线或离线时都可以继续自动交易。
- 所属玩家离线时，从 QShop 玩家数据中读取货币余额和限购信息，并检查货币、提供物品和交易限购次数。
- 离线交易不会重新检查 FTB Quests 和 Stage 条件。
- 支持 Action Bar 和聊天栏交易成功或失败提示。

### 使用方法

1. 放置自动收购箱并打开界面。
2. 在设置分页搜索并选择一个 QShop 交易项目。
3. 设置交易时间间隔和提示选项。
4. 如果选择的是以物换物项目，将需要的物品放入右侧的玩家提供物品容器。
5. 购入物品会输出到左侧的购入物品容器。

选择交易项目后，即使关闭 GUI，自动交易也会继续执行。

### 依赖

- Minecraft Forge 1.20.1
- [QShop](https://www.curseforge.com/minecraft/mc-mods/q-shop) 1.1.0 或更高版本，低于 2.0

### 安装

1. 安装 Minecraft Forge 1.20.1。
2. 安装 [QShop](https://www.curseforge.com/minecraft/mc-mods/q-shop)。
3. 将 `qshop-requester` 的 jar 文件放入 `mods` 文件夹。

### 协议

ARR（保留所有权利）。未经作者许可，不得重新发布、修改或将本 Mod 包含在其他项目中。
