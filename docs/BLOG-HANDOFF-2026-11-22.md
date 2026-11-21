# Photerest 经典版与 Alpha · 博客更新交接

核对日期：2026-11-22。交给下一位 Codex；**本轮只准备交接，不直接修改或部署博客。**

## 1. 用户最新决定：先看这一节

**GitHub 正常上传当前版本；博客暂不添加“本地 AI”和“联网搜图”，等待用户下一步安排。** 两者的发布范围不同，不能从完整 GitHub Release/CHANGELOG 自动同步所有功能到网站。

- 本次网页可更新：版本与下载、经典版玻璃自定义与旧菜单恢复、Alpha 视频画面串位修复、已有照片/视频浏览与原生安卓体验。
- 本次网页不要新增：本地 AI、自然语言搜索 BETA、模型下载、共享模型中心、迁移流程及硬件加速宣传；Google/百度联网识图、共享额度、历史搜索、搜图按钮与相关毛玻璃入口的介绍。
- 不新增这些功能的导航、板块、FAQ、截图、图标、网页示意、动效、配套组件下载、SEO 或 APP 列表卖点。相关实验代码和完整记录存在于 GitHub，也不代表可以用于博客。
- 保留当前网站已经有的内容和两种产品设计。产品版本升到最新，不意味着必须把所有功能同时公开介绍。

## 2. 接手入口与下载信息

博客工程：`C:\Users\1\Desktop\自我博客`，Astro；配置站点 `https://everythingyuan.de5.net`。

| 项目 | 经典版 | Alpha |
| --- | --- | --- |
| 更新前网站 / GitHub 基线 | 1.7.2 | 1.4.5 |
| 本次发布 | **2.2.1 / versionCode 32** | **1.8.2 / versionCode 25** |
| 名称 | Photerest | Photerest Alpha |
| 应用 ID | `com.photerest.app` | `com.photerest.app.material` |
| 客户端要求 | Android 8.0+ | Android 8.0+ |
| 许可 | 闭源、免费使用；GitHub 只发安装包和说明 | Apache-2.0 客户端开源 |
| 产品页 | `/apps/photerest/` | `/apps/photerest-alpha/zh/`、`/apps/photerest-alpha/en/` |
| Release | https://github.com/Max1426/Photerest/releases/tag/v2.2.1 | https://github.com/Max1426/Photerest-Alpha/releases/tag/v1.8.2 |
| APK | https://github.com/Max1426/Photerest/releases/download/v2.2.1/Photerest-2.2.1.apk | https://github.com/Max1426/Photerest-Alpha/releases/download/v1.8.2/Photerest-Alpha-1.8.2.apk |
| SHA-256 | `93d4811d55d2413baaf121521671b3d2b2d20c7a1646530cfdc757697ae10e9e` | `81c7d914dfbe6e8f0da5fedceba27114958b40979f3cb8d22429a5fcc3b3b6ff` |

对应 Release 均提供 `SHA256SUMS.txt`。两个产品可并排安装，各自保存资料、收藏与通知设置。发布时核对了包名、版本和签名；最新版真机覆盖升级尚未验收，不能宣传“所有数据迁移已实测”。

## 3. 经典版：本次网页允许介绍的内容

| 版本 | 页面可以采用的变化 |
| --- | --- |
| 1.7.3 | 统一首次安装与恢复默认的玻璃配置，覆盖升级保留已有参数。 |
| 2.2.0 | 引入按钮 / 上拉面板两套独立玻璃参数。这一版曾重制菜单，后来已撤回，不作为最终布局展示。 |
| 2.2.1 | 恢复 2.1.1 的原有上拉菜单布局、尺寸和原生容器；普通菜单玻璃默认关闭，可在设置开启。两套玻璃参数保留。 |

### 当前效果与文案

- 以 **2.2.1** 为准：原有菜单之上可选玻璃底面；不要使用 2.2.0 的重制菜单作为新版本主视觉，也不要写“菜单默认全部玻璃化”。
- 「设置 → 毛玻璃」可选「按钮 / 上拉面板」。两档分别保存液态 / 经典毛玻璃、折射、模糊、明暗、浓度、预设色和自定义颜色，分别恢复默认。两种模式分别保留模糊值。
- 「毛玻璃」「每日精选」全屏页与居中的编辑、确认和时间选择框不属于这次玻璃上拉菜单变化。
- Android 13+ 支持液态折射；Android 12 使用实时毛玻璃；Android 8–11 使用半透明遮罩。不能宣传所有系统都有同样折射效果。
- 照片与视频混排、静音预览、无字模式、原位过渡与每日精选可保留在功能总览，它们不是本次全部新增。

可直接采用：

> 熟悉的经典界面，多一点自己的选择。按钮和上拉面板可以分别调整玻璃效果，折射、模糊、明暗与颜色都由你决定。普通菜单仍默认保持原来的样式，需要时再开启玻璃底面。

旧文案中的“纯本地”“整个软件完全不上传”不要继续作绝对承诺，可改为“以本地图库为内容”“浏览、收藏和每日精选使用本机照片”。不借此次修正文案扩写本轮暂缓的功能。

## 4. Alpha：本次只补视频修复，保留原生安卓体验

本次页面的新版更新项是 **1.8.2 视频画面串位修复**：点开视频时，仅使用属于该视频的画面做过渡；正确首帧出现后清除封面，留白不再残留另一条视频。保留原位展开、缩回、播放进度、普通返回与预测返回取消。

不要把 1.6.0–1.7.2 或 1.8.0–1.8.1 中暂缓的功能写成网站新卖点；完整发布历史只用于核对。Alpha 中英文页同步更新版本、下载、视频稳定性说明。

| 中文 | English |
| --- | --- |
| **过渡，属于你点开的那一条视频。** 1.8.2 修复不同视频之间的封面串位。展开、播放和返回保持连贯，横视频的留白不再露出另一条视频。 | **Transitions stay with the video you opened.** Version 1.8.2 fixes mixed-up video covers. Opening, playback, and returning stay connected, without another video appearing in the letterboxed area. |
| **沿用熟悉的原生安卓体验。** 保留 Material 3 标准 / Expressive 风格、深浅色、可选系统动态色，以及照片从卡片展开和缩回的连续动画。 | **A familiar native Android experience.** Keep standard Material 3 and Expressive styles, light and dark themes, optional system colors, and continuous card-to-viewer photo transitions. |

原生安卓部分可以继续用 Material 图形、标准组件与系统返回的示意，不使用 Google/Meta 的整页素材或标志暗示合作。不要把“支持预测返回”描述成所有设备的手势都已经验证。

## 5. 实现入口、动效与素材规则

### 工程入口

- 经典版：`src/data/photerest.ts`（版本、哈希、更新）、`src/pages/apps/photerest.astro`（正文、SEO、下载）、`src/components/PhoterestGlass.astro`、`src/scripts/photerest.ts` 及相关样式。
- Alpha：`src/components/photerest-alpha/content.mjs`（版本与双语文案）、`src/pages/apps/photerest-alpha/[locale]/index.astro`、同组件目录的 `NativeAndroid.astro`、`motion.mjs`、`site.css`。
- 两版列表入口：`src/data/apps.ts`。经典版新简介可用“经典界面、照片与视频浏览，以及可分别调节的按钮和菜单玻璃”；Alpha 可用“原生 Material 照片与视频浏览，改进视频展开与返回的画面衔接”。不添加暂缓内容。

### 动效必须保留

- **经典版重点仍是液态玻璃**：沿用背景上下拖动、前景固定玻璃的交互，让访客看到边缘折射与中央清透；保留“液态玻璃 / 经典毛玻璃”对照。可补“按钮 / 上拉面板”的独立参数示意，以及普通菜单默认关闭的说明。不要重制应用菜单布局。
- **Alpha 用空间过渡区分风格**：浅色双栏首屏继续让照片从卡片原位展开、详情栏稍后淡入、停留后缩回；支持重播，手机框和下载按钮稳定。视频部分可补连贯过渡的图形示意，不引入本轮暂缓功能的流程演示。
- 网页演示只使用本地素材，不调用真实应用服务、不上传访客图片。明确标注“网页交互示意 / Interactive web demo”，不得冒充应用实录。
- 减少动态、手动关闭动效、无 JavaScript、后台或离屏时使用静态/暂停方案，内容及下载仍可用。拖动区域不要拦截整页滚动。

### 媒体版本与来源

- **产品版本与素材路径分开**。经典版现有 `photerestGlass.media` 指向 v1.7.2；Alpha `experienceMedia` 指向 v1.3.1、`videoMedia` 指向 v1.4.5。更新版本号时，不把全部路径机械替换成新版本导致 404。
- 保留原有四张用户提供的通知截图，标注 **vivo X300 Pro / OriginOS 7**，它们展示历史通知样式，不冒充新版菜单。
- 交接包中的 `video-entry-blue.png`、`video-detail-blue.png` 为 **Alpha 1.8.2 / Android 36.1 模拟器 / 生成测试视频**的实际验证截图，仅用于技术修复说明，不冒充真机实拍或真实图库。
- 2.2.0 菜单截图是已撤回布局，不作为 2.2.1 当前效果。旧录屏保留原版本和设备说明。缺少新版实拍时用标注清楚的网页示意，不捏造录屏。
- 不另行上传手机个人图库、开发附件或未获授权的照片。暂缓功能的图片与视频不加入页面。

## 6. 验收与接力

1. 同步两版下载、版本徽标、哈希、Release/源码链接、APP 列表、SEO、结构化数据与允许范围内的更新记录；Alpha 中英页面同步，语言切换保留锚点。
2. 从已发布 GitHub Release 核对资产，实际下载 APK 并与 SHA256SUMS 比对。源码与发布说明包含更多实现，不据此擅自增加网页卖点。
3. 检查 320 / 390 / 1440 宽度、两种主题、键盘、减少动态、动效开关与无 JS；旧新媒体无 404，视频 MIME 与需要的范围请求正常。
4. 运行现有 `check-app-pages.mjs`、`check-photerest.mjs`、`check-photerest-geometry.mjs`、产品主题/菜单检查和构建；按博客原流程部署，不改认证与服务凭据。
5. 交回桌面与手机各一段网站动效录屏，覆盖玻璃背景拖动与 Alpha 原位展开缩回，区分网页动画、模拟器与真机材料。
6. 专门检查页面源代码、SEO、FAQ、APP 卡片、导航和媒体目录，确保没有新增本地 AI 或联网搜图介绍。

验证边界：经典 **2.2.1 仅构建**，没有本版测试、Lint、模拟器或真机验收；2.2.0 的旧测试不可套用。Alpha **1.8.2 四项视频专项检查同轮通过**，最新 vivo 手势与实际声音仍待确认。不要写“全部功能均已通过真机测试”。

共同交接附件：`Photerest-Blog-Handoff-2026-11-22.zip`（两版 Release 都提供），包含本文件、网站用版本清单和上述两张技术验证图，不包含模型、私人照片、暂缓功能的宣传素材或凭据。
