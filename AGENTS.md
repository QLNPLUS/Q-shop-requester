# QShop Requester — 多版本开发约定

本仓库是**一个 git 仓库、多条版本分支**,每条分支各有独立 worktree。改动默认为"先落一条分支、测试通过后再迁移到其他分支"。

**本文件在所有分支上内容完全相同。** 修改时必须各分支同步提交同一内容,否则各分支上的 AI 会读到不同约定。

## 分支矩阵

| 分支 | worktree 路径 | 加载器 | MC | JDK | Gradle | 构建插件 |
|---|---|---|---|---|---|---|
| `forge-1.20.1` | `D:\projects\q_shop_requester\forge-1.20.1` | Forge | 1.20.1 | **17** | 8.1.1 | ForgeGradle `[6.0.16,6.2)` |
| `neoforge-1.21.1` | `D:\projects\q_shop_requester\neoforge-1.21.1` | NeoForge | 1.21.1 | **21** | 8.8 | ModDevGradle 2.0.141 |
| `neoforge-1.26.1.2` | `D:\projects\q_shop_requester\neoforge-1.26.1.2` | NeoForge | 26.1.2 | **25** | 9.2.1 | ModDevGradle 2.0.146 |

- **主工作树(持有 `.git` 目录)是 `neoforge-1.21.1`** —— 与本组织另一个项目 `q_shop` 相反(那边是 Forge 1.20.1)。其余是 linked worktree(`.git` 是文件)。三者共享同一对象库,在一个 worktree 里 commit 的提交可直接在另一个 worktree 里 `cherry-pick`,无需 fetch。
- 全部 tracking `origin`(`https://github.com/QLNPLUS/Q-shop-requester.git`)。**没有 fork 远程**。
- **本地分支名与远程分支名有一处不一致**:本地 `forge-1.20.1` 跟踪 `origin/master`,`origin/HEAD` 也仍指向 `origin/master`。推送这条分支必须显式写 `git push origin forge-1.20.1:master`,**不要**用裸 `git push`。
- `neoforge-1.26.1.2` 从 `neoforge-1.21.1` 分出。**它目前只是 1.21.1 代码的一个副本,26.1.2 适配尚未完成**(见下)。首次构建失败属预期。

## 本项目不抽 `common/`(已实测,勿重新评估)

实测依据(2026-09 复核,`forge-1.20.1` vs `neoforge-1.21.1`):

| 指标 | 结果 |
|---|---|
| Java 文件数 | 13 / 14,**合计约 2,690 LOC** |
| 零非 JDK import(可单编译共享的纯 Java)的文件 | **0 / 13** |
| 两分支字节相同的文件 | **3 / 13** |

那 3 个相同的文件是 `RequesterEmiCompat`、`RequesterLayoutDebug`、`RequesterTextures`,规模都很小;抽取它们只能消掉不到 300 行,却要引入跨分支共享源码树的构建复杂度。

差异集中在真实平台鸿沟上,必须各留一份:

| 文件 | 差异行数 | 性质 |
|---|---|---|
| `RequesterNetwork.java` | 348 | Forge `SimpleChannel`/`NetworkEvent` ↔ NeoForge `CustomPacketPayload`+`PayloadRegistrar` |
| `RequesterBlockEntity.java` | 120 | 方块实体 NBT/能力 ↔ DataComponents/AttachmentType |
| `RequesterService.java` | 74 | 请求逻辑中的平台调用 |
| `RequesterMod.java` | 60 | 模组入口与事件注册 |
| `RequesterScreen.java` | 42 | GUI |

**结论:本项目保持各分支自包含。** 若将来规模显著增长(例如 Java 文件数翻倍、或出现真正零依赖的核心逻辑),再按 `minecraft-mod-development` skill 的量化判据重新测量 —— 判据是"零依赖 + 字节相同的文件占比",不是"感觉重复"。

## 工作流:加新功能(默认流程,不必每次询问)

1. **只改一条分支。** 默认 `forge-1.20.1`;用户指定了别的分支就用指定的那条。
2. **改动必须先提交(commit)**,再谈迁移。
3. **触发迁移的说法**:用户说"测试通过 / 可以了 / 同步到其他版本 / 另外两个版本也加上"等,即为迁移信号 —— 此时**立即**对其余分支执行 `git cherry-pick -x <sha>`,不要等用户再次点名 `cherry-pick`。
4. **迁移前逐分支判定适用性,并明确说明结论**(见下节)。
5. **禁止把一个改动在另一条分支上手工重写一遍。**

## 迁移纪律(硬规则)

**禁止手工重写。** 跨版本搬运只能用:

```powershell
git -C D:\projects\q_shop_requester\<目标worktree> cherry-pick -x <源分支SHA>
```

`-x` 会在提交信息里记录来源 SHA,建立可追溯链接。手工重写的后果不是"多打一遍字",而是产出**互不相关、无法追溯**的提交,漂移会无声累积。

**冲突是信息,不是麻烦。** cherry-pick 冲突明确指给你"这里已与源分支分叉",那正是需要知道的位置。**不要通过重新实现来"解决"冲突。**

**缺陷修复是双向的。** 若在非默认分支上定位并修好了 bug,要**先 cherry-pick 回默认分支**,再流向其他分支。

## 迁移前的适用性判定

对每个目标分支给出结论,三类之一:

- **适用**(与加载器无关的逻辑改动)→ `git cherry-pick -x`
- **不适用**(平台相关:能力系统、`RequesterNetwork`、`RequesterBlockEntity`、loader metadata、`mods.toml` / `neoforge.mods.toml`)→ **跳过并说明原因**
- **需适配**(API 改名,如 26.1.2 的 `GuiGraphicsExtractor` / `Identifier` / `.text()`)→ **先 cherry-pick,再显式处理冲突**;绝不预先重写

## 已知平台鸿沟(真实差异,不要试图消除)

| | Forge 1.20.1 | NeoForge | 仅 26.1.2 |
|---|---|---|---|
| 网络 | `SimpleChannel` / `NetworkEvent` | `CustomPacketPayload` + `PayloadRegistrar` | — |
| 方块实体数据 | `CompoundTag` / `Capability` | DataComponents / `AttachmentType` | — |
| 元数据 | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` | — |
| 渲染 | `GuiGraphics` | `GuiGraphics` | `GuiGraphicsExtractor` |
| 标识符 | `ResourceLocation` | `ResourceLocation` | `Identifier` |
| 文字 | `drawString` | `drawString` | `.text()` |
| 贴图 | `.blit(tex, ...)` | `.blit(tex, ...)` | `.blit(RenderPipelines.GUI_TEXTURED, tex, ...)` |
| 矩阵栈 | `pose().pushPose()` | `pose().pushPose()` | `pose().pushMatrix()` |
| tooltip | `renderTooltip` | `renderTooltip` | `setTooltipForNextFrame` |

搬运调用点时**逐个核对目标版本签名,不要直接复制** —— 这些错误能编译通过但运行时画面错。26.1.2 的 `blit` 参数顺序、六位 ARGB 文字色(alpha=0 → 不可见)、局部缩放下的 tooltip 坐标,见 `forge-gui-layering` skill。

**本项目的 GUI 代码是 `RequesterScreen.java`(778 LOC)+ `RequesterTextures.java` + `RequesterLayoutDebug.java`**,是 26.1.2 适配的主要工作量所在。

## 跨仓库编译依赖(最容易踩的坑)

**本项目不是自包含的:它编译时要引用 QShop 主模组(`com.qshop.shop.*`、`com.qshop.net.*`、`com.qshop.client.ShopScreen` 等)。**

`build.gradle` 里使用的是一条**指向兄弟目录的相对路径**:

| 分支 | 依赖声明 |
|---|---|
| `forge-1.20.1` | `compileOnly fg.deobf(files('../Q-shop-forge-1.20.1/build/libs/qshop-forge-1.20.1-1.2.4.jar'))` |
| `neoforge-1.21.1` | `compileOnly files('../Q-shop-neoforge-1.21.1/build/libs/qshop-neoforge-1.21.1-1.2.4.jar')` |

**CI 能工作**是因为 workflow 把两个仓库并排 checkout:

```yaml
- uses: actions/checkout@v4
  with: { repository: QLNPLUS/Q-shop, ref: neoforge-1.21.1, path: Q-shop-neoforge-1.21.1 }
- uses: actions/checkout@v4
  with: { ref: ..., path: Q-shop-requester-neoforge-1.21.1 }
```

于是那个相对路径正好命中。

**本地布局下它会失效**,因为 QShop 主模组现在是**另一个项目文件夹**(`D:\projects\q_shop\...`),不再是本项目的兄弟目录。

**因此:本地跑构建前,必须先确认那条路径能解析到真实的 jar。** 两种处置:

- 把 QShop 主模组对应分支的 jar 放到该相对路径上,或
- 用 `-P` 覆盖依赖路径(若已改为可配置),例如
  `.\gradlew.bat build -PqshopJar=D:/projects/q_shop/neoforge-1.21.1/build/libs/qshop-neoforge-1.21.1-1.6.2.jar`

**注意版本号 1.2.4**:依赖声明写死的是 `1.2.4`,而 QShop 主模组已发布到 `1.6.2`。本地构建前需要确认用哪个版本的 QShop jar —— 换版本可能因 API 变化而编译失败。**不要在没有验证的情况下把版本号改大。**

**改动这条依赖路径或版本号时,必须同步检查 CI workflow** —— 分支名与路径都是 CI 的契约。

## Release Tag

格式:**`v<version>-<loader>-<mcversion>`**,前缀统一用 `v`:

```
v1.4.0-forge-1.20.1
v1.4.0-neoforge-1.21.1
```

git tag 是仓库级唯一的,而本仓库锁步发布 —— 只打一个 `v1.4.0` 无法指认是哪个加载器。

## 构建

- **JDK 由 `gradle.properties` 里的 `org.gradle.java.home` 强制钉住,且已提交进 git。** 这是本项目比 `q_shop` 更可靠的地方:不需要手动设 `JAVA_HOME`。**不要把这个属性删掉或改成别的版本**;换 JDK 请改这一行并提交到对应分支。
- 各分支串行构建,不要并行 —— 会争用 Gradle 缓存与内存。
- 使用各自的 Gradle wrapper(`.\gradlew.bat`),不要用系统 gradle。
- 发布产物名:`qshop-requester-forge-1.20.1-<ver>.jar` / `qshop-requester-neoforge-1.21.1-<ver>.jar`。
- `pack.mcmeta` 在**仓库根**(不在 `src/main/resources` 下),`pack_format` 随目标版本变化,各分支各自维护。

## CI

`.github/workflows/curseforge-publish.yml`(各分支一份)按 ref 分别 checkout。**分支名是 CI 的契约**:若将来把 `origin/master` 改名,workflow 里 Forge 任务的默认 ref 与 `origin/HEAD` 需要同步更新。

## 推送

本仓库只有 `origin`,没有 fork 远程。**未经用户明确要求不要推送**,尤其不要 force-push 或推送分支改名。

## 沙箱注意

在 linked worktree(`forge-1.20.1`、`neoforge-1.26.1.2`)里工作时,项目根的判定是**该 worktree 自身** —— 不会上溯到父目录。因此 `AGENTS.md` 必须**每条分支各提交一份**,放在 `D:\projects\q_shop_requester\` 根目录(那里没有 `.git` 标记)是读不到的。
