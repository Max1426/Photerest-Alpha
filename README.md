# Photerest Alpha

基于 Kotlin、Jetpack Compose 和 Material 3 的开源 Android 照片与视频浏览应用。支持收藏、每日精选、无字模式、原位过渡，以及独立的本地 AI 搜索实验和 Google 联网搜图。

## 最新版本：1.8.2

[下载 Alpha APK](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.8.2/Photerest-Alpha-1.8.2.apk) · [Release](https://github.com/Max1426/Photerest-Alpha/releases/tag/v1.8.2) · [校验值](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.8.2/SHA256SUMS.txt) · [验证记录](docs/verification-1.8.2.md)

- 修复不同视频的封面串位，保留视频展开、缩回、预测返回和进度延续。
- Google 联网搜图在每次新搜索前确认上传与一次共享额度消耗；历史结果保存在本机，重复查看免费。结果面板的拖动和首次加载已修复。
- 照片详情搜索按钮默认使用 Material 取色毛玻璃，可关闭或调节 0–30 dp（默认 14 dp）。Android 12 以下使用实色 Material 按钮。新加入的搜图界面采用 Material 3 Expressive。
- 主页、关联相册与收藏支持照片和视频混排、静音预览、分享和打开原图/视频。无字模式默认开启。

应用 ID `com.photerest.app.material`；最低 Android 8.0；可与[经典版](https://github.com/Max1426/Photerest)并排安装。覆盖升级需要签名一致。

## 本地 AI 搜索实验与模型中心

本地 AI 需要 **Android 12+ ARM64**；从 1.7.2 起必须先安装同签名的 [Photerest AI Hub 0.1.0](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.8.2/Photerest-AI-Hub-0.1.0.apk)。模型约 485 MB，**未包含在 APK 中**。中心准备模型后，本地索引与查询在手机运行。共享中心、迁移与跨应用推理仅构建完成，仍待手机验收；不要将其视为已经验证稳定的生产功能。经典版尚未接入中心。

[模型中心源码（Apache-2.0）](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.8.2/Photerest-AI-Hub-0.1.0-source.zip) · [安装与迁移说明](docs/AI-HUB-SETUP.md)。客户端 AAR 及对应源码在 `app/libs`，Alpha 构建不依赖桌面其他目录。模型另有自身许可，参见模型中心 NOTICE。

Google 联网搜图与本地 AI 独立：联网搜图经确认后上传缩放副本，并与经典版共用服务端额度，不是离线 AI，也不等同于 Google Lens 网页。

## 构建

JDK 17+、Android SDK 35。配置本机 SDK 路径后：

```powershell
.\gradlew.bat :app:assembleDebug -PimageSearchOrigin=https://photerest-image-search.songy9539.workers.dev
```

不传 `imageSearchOrigin` 时，联网识图服务未配置；本地浏览不受影响。输出在 `app/build/outputs/apk/debug/`。自行构建默认使用本机调试证书，可能无法覆盖官方发布包或连接官方 Hub；自行编译中心与客户端时应使用同一签名。

## 文档与许可

[更新记录](CHANGELOG.md) · [博客更新交接](docs/BLOG-HANDOFF-2026-11-22.md) · [Apache-2.0](LICENSE) · [第三方声明](NOTICE)。Google 标志归 Google LLC 所有，模型许可与客户端源码许可独立。测试资源为生成的几何图片与视频；模拟器验证不代表真机验收。
