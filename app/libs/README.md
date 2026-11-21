# Shared AI client

`photerest-ai-client-0.1.0.aar` 是轻量 AIDL 客户端，不含模型和推理原生库。
对应源码在 `photerest-ai-client-0.1.0-sources.zip`。完整独立工程为桌面的 `Photerest-AI-Hub`，不需要该工程也能构建此 Alpha 工程。
先使用独立工程 `:client:assembleRelease` 构建 AAR；替换这里的 AAR 后，再执行 Alpha `:app:assembleDebug`。中心和客户端需相同签名。
