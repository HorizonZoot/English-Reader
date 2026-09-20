<p align="center">
  <img src="assets/app-icon.png" alt="English Reader 应用图标" width="200" />
</p>

# English Reader

> **本项目由 AI 开发。** 从需求拆解、架构设计、编码、测试到代码复查，绝大部分工作由 AI 编程助手完成，人类开发者负责提出需求、做产品与技术决策、审阅与验收。阅读本仓库代码时请把这一点纳入考量。

English Reader 是一个用于**沉浸式英文阅读**的 Android 应用：导入自己的文章或整本 EPUB，点句查看译文、长按查词、保存生词，并可接入自己的 AI 服务商做句子解释与全文翻译。

所有内容都来自用户自己导入的文件，应用不内置书城、不上传文章、不收集用户数据。AI 能力需要用户自备 API Key，默认完全不启用。

---

## 功能

### 阅读

- **两种阅读模式**：垂直滚动与横向翻页，可随时切换。切换后按**字符锚点**恢复到同一位置，而不是页码或像素偏移。
- **点击选句**：基于 `BasicText` + `AnnotatedString` + `TextLayoutResult` 的命中测试，保留原文精确偏移。
- **长按查词**：两阶段交互，先定位单词再弹出释义。
- **双语段落对照**：英文主文本 + 克制的中文次文本，按段落逐一配对。
- **朗读（TTS）**：内置 LibriTTS 离线模型，默认 Jen 音色，无需另行下载；设置中可下载 Kokoro，阅读页可选择具名音色或系统语音、调整语速（0.5x–2.0x）。支持从当前句开始的连续朗读、暂停、上一句/下一句。
- 字号三档、浅色/深色/跟随系统主题。

### 词典与生词

- **离线词典**：内置 7,005 条基础词库（`assets/dict_base.tsv`），无需联网。
- **词形还原**：查 `lives` 能还原到 `live`；歧义词并列展示多个原形释义。
- **在线兜底**：离线未命中时调用 Free Dictionary API。
- **发音**：优先真人音频，失败时使用所选 TTS 音色；单词语速固定为正常速度。
- **生词本**：按字母或时间分组；删除文章不会连带删除已保存的生词。

### 导入

- **单篇文章**：TXT、Markdown、EPUB，也支持直接粘贴文本。
- **整本 EPUB**：解析为书架条目 + 章节列表，支持目录导航与章节内位置恢复。
- 导入有明确的体积预算，超限会给出可操作的错误说明（区分「文章过长」与「章节过长」）。

### AI（需自备 API Key，默认关闭）

- **多 Profile 管理**：可配置多个服务商，随时切换活跃 Profile。内置 DeepSeek、Kimi、智谱的默认值，也支持任意 **OpenAI 兼容**端点。
- **句子解释 / 句子翻译 / 整篇解释**：在阅读页浮窗内直接触发。
- **全文翻译**：逐段翻译整篇文章或整本书的全部章节，**像可恢复下载一样**持久化进度——中断后只继续未完成的段落，已成功的段落不会重复请求（也就不会重复计费）；失败项可单独重试；全部成功后才一次性写入文章译文。
- **语义缓存**：缓存键只由「决定输出的输入」构成，因此同一句话在不同文章里共享缓存；30 天固定 TTL、500 行容量上限。

### 隐私与安全

- API Key 只经 `EncryptedAiCredentialStorage` 存储（AndroidX Security Crypto），**不进** Room、DataStore、源码、日志或 Compose 保存状态。
- 不记录 prompt、请求/响应体、文章与句子原文、缓存译文、完整端点 URL 或原始异常信息。
- `usesCleartextTraffic="false"`，不支持明文 HTTP。
- 更新检查只从 GitHub 公开 API **读取** Release 信息，不上传任何内容，也不携带设备或用户标识。
- AI 失败按**类型化异常与其 cause 链**分类，而非匹配本地化异常文本。

---

## 技术栈

| 领域 | 选型 |
| --- | --- |
| 语言 / 工具链 | Kotlin 1.9.22、JDK 17、AGP 8.3.0、Gradle 9.0.0（Wrapper） |
| UI | Jetpack Compose（BOM 2024.02.00，编译器 1.5.10）、Material 3 |
| 架构 | MVVM + Repository，单 Activity + Navigation Compose |
| DI | Hilt 2.50 |
| 持久化 | Room 2.6.1（**schema v6**，导出 schema + 迁移测试）、DataStore Preferences 1.0.0 |
| 凭据 | AndroidX Security Crypto 1.1.0-beta01 |
| 网络 | Retrofit 2.9.0、OkHttp 4.12.0、Moshi 1.15.0 |
| EPUB | Readium `shared` + `streamer` 3.0.3（仅用解析，**刻意不使用 Navigator**） |
| 离线语音 | sherpa-onnx 1.13.8 + ONNX Runtime（仅 arm64-v8a；内置 espeak-ng 使分发受 GPLv3 约束） |
| 异步 | Coroutines、Flow、Channel |
| 测试 | JUnit4、MockK 1.13.9、Robolectric 4.16.1、Turbine 1.0.0、MockWebServer、Compose UI Test、Room Testing |
| SDK | minSdk 24，compile/target SDK 34，启用 core library desugaring |

> Readium 锁在 3.0.3 是有原因的：3.1+ 用 Kotlin 2.1 元数据（本项目 1.9.22 读不了），3.2+ 要求 `compileSdk 36`（本项目 34）。升级 Readium 必须先抬 Kotlin 与 compileSdk。

### 分层约定

```
Compose UI  ->  Hilt ViewModel  ->  Repository / feature facade
                                 ->  Room / DataStore / 加密凭据 / Retrofit
```

ViewModel 持有屏幕状态与异步协调；Compose 层尽量无状态，用 `collectAsStateWithLifecycle()` 收集；一次性反馈走 `Channel` / `SharedFlow`；纯归一化、解析、身份与策略逻辑保持 JVM 可测。

**潜在计费的 AI 请求由进程级作用域持有**：关闭面板只解除 UI 观察，不会取消已经发出的付费请求。

### 文件树

```
EnglishReader/
├── app/
│   ├── libs/sherpa-onnx-1.13.8.aar             # 离线语音运行时（arm64-v8a，内置 GPL-3.0 的 espeak-ng）
│   ├── schemas/io.github.zoot.englishreader.data.database.EnglishReaderDatabase/
│   │   └── 3.json · 4.json · 5.json · 6.json   # 导出的 Room schema（v1/v2 已不可考）
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/dict_base.tsv            # 内置离线词典，7,005 条
│   │   │   ├── assets/tts/                     # 内置 LibriTTS 模型 ZIP（默认 Jen 音色）
│   │   │   ├── assets/licenses/                # sherpa-onnx / ONNX Runtime / espeak-ng / commons-compress 许可原文
│   │   │   ├── java/io/github/zoot/englishreader/
│   │   │   │   ├── MainActivity.kt             # 单 Activity + Navigation 图
│   │   │   │   ├── EnglishReaderApp.kt         # Hilt Application
│   │   │   │   ├── core/                       # SentenceRange、ReadingPaginator
│   │   │   │   ├── data/
│   │   │   │   │   ├── ai/                     # AI facade、prompt 策略、缓存身份、错误分类
│   │   │   │   │   ├── audio/                  # 发音音频缓存
│   │   │   │   │   ├── dao/                    # Room DAO（含事务方法）
│   │   │   │   │   ├── database/               # Database 定义与 Migrations
│   │   │   │   │   ├── dictionary/             # 内置词典与词典包安装
│   │   │   │   │   ├── entity/                 # Room 实体
│   │   │   │   │   ├── importer/               # TXT/MD/EPUB 解析、导入预算与校验
│   │   │   │   │   ├── local/                  # DataStore 偏好、加密凭据、AI Profile
│   │   │   │   │   ├── remote/ai/              # OpenAI 兼容 transport、DTO、端点解析
│   │   │   │   │   ├── remote/dictionary/      # Free Dictionary API
│   │   │   │   │   ├── remote/update/          # GitHub Release 查询
│   │   │   │   │   ├── tts/                    # 离线语音模型清单、安装器与解包校验
│   │   │   │   │   ├── update/                 # 版本号比较与更新说明格式化
│   │   │   │   │   └── repository/             # 文章、书籍、生词、词典、AI、全文翻译、更新
│   │   │   │   ├── di/                         # Hilt 模块（DB、网络、设置、协程作用域）
│   │   │   │   ├── model/                      # 跨层不可变模型与状态
│   │   │   │   ├── ui/
│   │   │   │   │   ├── component/              # InteractiveText、浮窗、各类 Sheet
│   │   │   │   │   ├── dialog/                 # 导入、粘贴、更新提示对话框
│   │   │   │   │   ├── screen/                 # 阅读、书架、目录、生词本、设置
│   │   │   │   │   └── theme/                  # Material 3 主题
│   │   │   │   ├── util/                       # 分句、分段、词形还原、TTS、缓存键
│   │   │   │   └── viewmodel/                  # 各屏 ViewModel 与 AI 面板协调器
│   │   │   ├── res/
│   │   │   │   ├── mipmap-*/                   # 启动图标：5 档密度 + anydpi-v26 自适应
│   │   │   │   ├── values/                     # 用户可见文案、颜色、主题
│   │   │   │   └── values-night/               # 深色主题
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                               # JVM + Robolectric 测试
│   │   ├── testDebug/
│   │   │   ├── java/                           # 真实 EPUB 的导入与阅读链路测试
│   │   │   └── resources/readium/public/       # EPUB 固件与来源说明 SOURCE.md
│   │   ├── androidTest/                        # Room、迁移、Compose、Hilt 仪器测试
│   │   └── debug/java/…/importer/spike/        # 合成 EPUB 固件（testDebug 与 androidTest 共用）
│   ├── build.gradle.kts
│   └── proguard-rules.pro                      # release 混淆与 keep 规则
├── assets/app-icon.png                         # 应用图标源图（1254×1254），mipmap 资源由它生成
├── gradle/
│   ├── libs.versions.toml                      # 版本目录
│   └── wrapper/                                # Gradle Wrapper
├── tools/epub-corpus/                          # EPUB 语料调查工具（语料按需下载，不进仓库）
├── AGENTS.md                                   # 工程规范与项目不变量
├── LICENSE                                     # MIT
├── README.md · THIRD-PARTY-NOTICES.md
├── build.gradle.kts · settings.gradle.kts · gradle.properties
├── gradlew · gradlew.bat
└── .gitignore · .gitattributes
```

---

## 构建与测试

需要 **JDK 17** 与 **Android SDK 34**。从仓库根目录执行（Windows 用 `gradlew.bat`，其他平台用 `./gradlew`）：

```powershell
.\gradlew.bat :app:compileDebugKotlin          # 生产代码编译
.\gradlew.bat :app:testDebugUnitTest           # JVM + Robolectric 测试
.\gradlew.bat :app:lintDebug                   # 静态检查
.\gradlew.bat :app:assembleDebug               # 打 debug 包
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest   # 需要真机或模拟器
```

### 配置 AI（可选）

应用不附带任何 API Key。在「设置 → AI 配置」中新建 Profile，选择服务商模板或填写任意 OpenAI 兼容端点，填入自己的 Key 并做一次连接测试即可。Key 只存在设备的加密存储中。

---

## 许可

项目自有源码沿用 [MIT License](LICENSE)。包含 sherpa-onnx / espeak-ng 的应用二进制分发
同时受 **GPLv3** 约束，不能按「仅 MIT」发布；须提供相应源码、构建材料及第三方许可声明。
具体来源与许可原文见 [第三方许可](THIRD-PARTY-NOTICES.md)。

### 第三方资源

离线词典（`app/src/main/assets/dict_base.tsv`）的词条由 [ECDICT](https://github.com/skywind3000/ECDICT) 提取整理，该项目同样采用 MIT 许可。其许可原文与版权声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
