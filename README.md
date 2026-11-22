# Photerest Alpha

Kotlin、Jetpack Compose 与 Material 3 的开源 Android 照片/视频浏览应用。com.photerest.app.material，Android 8+，Apache-2.0。与经典版可并排安装，资料、收藏、偏好和索引独立。

## 最新测试版 1.10.1（29）

[测试发布](https://github.com/Max1426/Photerest-Alpha/releases/tag/v1.10.1) · [APK](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.10.1/Photerest-Alpha-1.10.1.apk) · [校验值](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.10.1/SHA256SUMS.txt) · [验证记录](docs/verification-1.10.1.md)

- φ 智能默认关闭，统一本地 AI 与仅 Google 的 Beta 扩展。关闭保留模型、索引、授权和历史；开启有可重播的原生指南。
- φ 抽屉在所在窗口显示，统一进出场和拖动，避免内容反复排版和系统栏切换；后方底栏保持原位。新界面为 Material 3 Expressive。
- 联网另需设备授权，新请求先确认上传及一次额度，历史免费复用。公开安装不授予联网权限，本地 AI 不受该授权限制。
- 保留静音视频预览、详情有声、原位过渡/预测返回、收藏、每日精选、无字模式和可调 Material 取色搜索按钮玻璃。
- 本版新增流程的 vivo 覆盖验收仍待完成，既有模拟器和单元测试范围详见验证记录。

## 正式发布记录

[1.8.2 Release](https://github.com/Max1426/Photerest-Alpha/releases/tag/v1.8.2) 保留为 Latest；1.10.1 是 Pre-release。

## 共享 AI 中心

本地 AI 需 Android 12+ ARM64 和同签名 [Hub 0.2.0](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.10.1/Photerest-AI-Hub-0.2.0.apk)。模型约 485MB，不在 APK/源码中；中心保存一份模型和共享运行时，客户端各存索引。已有模型复用，覆盖升级不先卸载。

[中心源码](https://github.com/Max1426/Photerest-Alpha/releases/download/v1.10.1/Photerest-AI-Hub-0.2.0-source.zip) · [安装与迁移](docs/AI-HUB-SETUP.md)。SDK AAR/源码位于 app/libs，构建不依赖其他桌面工程；模型与运行库各有条款，不宣称 NPU。用户已有中心调用成功反馈，不能代替本版新流程验收。

本地管理支持选择 APK 并请求系统安装，未配置组件自动下载。清理索引、卸载中心独立确认；总开关不删除模型。

## 构建

JDK 17+、Android SDK 35，设置 ANDROID_HOME 或自己的 local.properties：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PimageSearchOrigin=https://photerest-image-search.songy9539.workers.dev
```

输出 app/build/outputs/apk/debug/app-debug.apk。不传 imageSearchOrigin 时联网服务未配置；公开地址不代表已获得使用授权。自行构建使用本机证书，覆盖发行包或连接中心需签名一致。

[更新记录](CHANGELOG.md) · [Apache-2.0](LICENSE) · [NOTICE](NOTICE)。Google 标志归 Google LLC。测试素材为既有公开模拟器夹具，不上传私人媒体；网站和博客本次不变。
