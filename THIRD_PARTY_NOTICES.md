# 第三方组件

Wool Ledger 的 MIT 许可不替代第三方组件的许可。修改或分发时，请保留组件自带的版权、许可和声明。

| 组件 | 用途 | 上游与许可 |
| --- | --- | --- |
| sherpa-onnx 1.13.7 | Android 离线语音推理；AAR 位于 `android/WoolLedger/app/libs/` | [上游项目](https://github.com/k2-fsa/sherpa-onnx)，[Apache-2.0 文本](docs/licenses/sherpa-onnx-Apache-2.0.txt) |
| AndroidX AppCompat / RecyclerView / Core | Android 界面与兼容支持 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，Apache-2.0 |
| Material Components for Android | 界面组件 | [上游项目](https://github.com/material-components/material-components-android)，Apache-2.0 |
| Kotlin Standard Library | sherpa-onnx Java 接口运行时 | [Kotlin](https://github.com/JetBrains/kotlin)，Apache-2.0 |
| Apache Commons Compress / Commons IO | 语音模型归档解压 | [Apache Commons](https://commons.apache.org/)，Apache-2.0 |
| XZ for Java | 归档支持 | [上游项目](https://tukaani.org/xz/java.html)，Public Domain |
| Gradle Wrapper | 构建引导 | [Gradle](https://github.com/gradle/gradle)，Apache-2.0 |

语音模型 `sherpa-onnx-paraformer-zh-small-2024-03-09` 从 [sherpa-onnx 模型发布页](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) 下载，不保存在 Git 仓库。模型权重及 AAR 中的原生依赖遵循各自上游声明，不能因应用采用 MIT 许可而视为重新授权。重新分发模型或二进制时，应一并保留包内许可文件。

具体依赖版本见 `android/WoolLedger/app/build.gradle`，传递依赖可用 `./gradlew :app:dependencies` 查看。
