# Photerest Alpha

Photerest 的开源 Android 照片浏览实验版，使用 Kotlin、Jetpack Compose 和 Material 3。相册浏览、收藏和推荐设置保存在本机。

- [下载 Photerest Alpha](https://github.com/Max1426/Photerest-Alpha/releases)
- [经典正式版下载与更新记录](https://github.com/Max1426/Photerest)

## 1.4.5

- 首页、关联相册和收藏支持照片与视频混排、静音预览、播放、分享和收藏。视频详情可用系统相册或播放器打开原视频。
- 视频支持原位展开、缩回和系统预测返回。「设置 → 偏好 → 在瀑布流中显示视频」可切回纯照片浏览。

- 无字模式默认开启，隐藏照片卡片下方的文件名和菜单。可在「设置 → 外观」关闭。
- 主页和个人页使用上下栏渐变，Android 12 及以上支持背景模糊。
- 从首页、相册或收藏打开照片时，从原卡片展开。返回时从当前缩放和拖动后的画面缩回原处。支持预测返回的系统可跟手缩回或取消。
- 支持相册关联、双指缩放、分享、收藏和打开原图。
- 淡紫色启动图标采用手写 P 和斜体 α。外观设置支持标准 Material 3 与 Material 3 Expressive。

Alpha 使用独立应用 ID com.photerest.app.material，可以与经典正式版并排安装。最低支持 Android 8.0。覆盖安装需要使用与已安装版本相同的签名。

## 构建

安装 JDK 17 和 Android SDK Platform 35，在 Android Studio 打开本仓库，或设置 ANDROID_HOME 指向本机 Android SDK 后运行 Gradle Wrapper。

    ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

Windows 使用 gradlew.bat。APK 位于 app/build/outputs/apk/debug/。自行构建的 APK 使用本机调试签名。

## 许可

Alpha 源码使用 Apache License 2.0，完整条款见 [LICENSE](LICENSE)。第三方声明见 [NOTICE](NOTICE) 和应用资源中的 [许可文件](app/src/main/assets/licenses)。设备测试使用为此仓库生成的几何图案。
