# Photerest Alpha

Photerest 的开源 Android 照片浏览实验版，使用 Kotlin、Jetpack Compose 和 Material 3。相册浏览、收藏和推荐设置保存在本机。

- [下载 Photerest Alpha 1.1.0](https://github.com/Max1426/Photerest-Alpha/releases/tag/v1.1.0)
- [经典正式版下载与更新记录](https://github.com/Max1426/Photerest)

## 1.1.0

- 从首页、相册或收藏打开照片时，从原卡片展开。返回时从当前画面缩回原处，保留列表位置。
- 支持相册关联、双指缩放、分享、收藏和打开原图。
- 淡紫色启动图标采用手写 P 和斜体 α。外观设置支持标准 Material 3 与 Material 3 Expressive。

Alpha 使用独立应用 ID com.photerest.app.material，可以与经典正式版并排安装。最低支持 Android 8.0。发布的 APK 沿用原 Alpha 的调试签名，可从 1.0.1 覆盖安装。

## 构建

安装 JDK 17 和 Android SDK Platform 35，在 Android Studio 打开本仓库，或设置 ANDROID_HOME 指向本机 Android SDK 后运行 Gradle Wrapper。

    ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

Windows 使用 gradlew.bat。APK 位于 app/build/outputs/apk/debug/。自行构建的 APK 使用本机调试签名。

## 许可

Alpha 源码使用 Apache License 2.0，完整条款见 [LICENSE](LICENSE)。第三方声明见 [NOTICE](NOTICE) 和应用资源中的 [许可文件](app/src/main/assets/licenses)。设备测试使用为此仓库生成的几何图案。
