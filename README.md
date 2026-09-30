<div align="center">

# 错题本（Android）

### 拍下错题，它自己认出来、整理好、排版成能打印的 A4。

单机 Android App：**拍照 / 相册 / PDF → MinerU 解析（公式、表格、手写）→ 大模型纠错整理 → 人工编辑 → 本地错题本 → 勾选打印 A4 PDF**。

不上云、不注册账号，Key 只存在本机。

**[解决什么问题](#解决什么问题) · [怎么用](#上手使用) · [技术栈](#技术栈与工程规模) · [工程记录](docs/DECISIONS.md)**

</div>

---

## 解决什么问题

纸质错题本的流程是：抄题 → 写答案 → 定期翻。但对理科题，真正费时间的不是抄，是**誊写公式和图表**——
一个手写公式抄错符号，这道题就白抄了。

这个 App 把录入这一步交给 OCR + 大模型：

```
拍照 ──▶ 自研裁剪页（框住题目，排除页面噪声）
      ──▶ MinerU v4 解析（公式 / 表格 / 手写体）
      ──▶ 大模型纠错整理成结构化 JSON
      ──▶ 人工编辑确认
      ──▶ 本地 Room 入库
      ──▶ 勾选题 → 生成 A4 PDF（可留白重做 / 可隐藏答案）
```

关键取舍：**识别结果一律进人工编辑页，不直接入库。** OCR 和大模型都会错，
但它们错得很具体（某个公式抄错、某段解释漏了），人在编辑页一眼能看出来；
直接入库的错题，复习时才暴露，那时已经浪费了。

## 核心能力

| | |
|---|---|
| **三种录入入口** | 拍照（3×3 网格取景 + 自研裁剪页）/ 相册批量（一次最多 20 张）/ PDF（文本 PDF 直接抽文字，图片 PDF 逐页走 MinerU，可指定页码范围） |
| **噪声排除** | 裁剪页拖手柄框住题目，框外压暗；识别完成后，Markdown 里的 `![]()` 插图引用做成可删 chip，一键剔除并回写 |
| **进度可见** | 上传 → MinerU 解析 → AI 整理，阶段来自数据库；失败可重试，或「跳过 AI 直接用原始文本」 |
| **一题多识别** | 一次识别可能整理出多道题，编辑页顶部左右箭头翻页；带原始识别文本对照 |
| **复习打卡** | 详情页复习打卡（会 / 模糊 / 不会）、标记已掌握；艾宾浩斯 1/2/4/7/15 天五轮提醒，`WorkManager` 每日推送 |
| **筛选与撤销** | 列表筛选、搜索，左滑删除可撤销 |
| **打印** | 勾选题目后可选「含原图 / 显示答案解析 / 留白重做」，生成 A4 PDF 到 `Download/错题本/`，可分享或打开 |
| **备份恢复** | zip = 数据库 + 全部文件，覆盖式恢复 |

## 技术栈与工程规模

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material3，全中文界面 |
| 架构 | 单 Activity + Navigation-Compose；MVVM（ViewModel + StateFlow + 不可变 UiState） |
| 网络 | Retrofit + OkHttp + kotlinx.serialization |
| 本地存储 | Room（KSP，Schema 导出）+ DataStore（非敏感设置） |
| 机密存储 | `EncryptedSharedPreferences`（API Key） |
| 相机 / 图像 | CameraX + 自绘拍照页 / Coil |
| 公式渲染 | WebView + KaTeX |
| PDF | 自写零依赖文本抽取器 + `PdfRenderer` 栅格化 + App 内生成 |
| 版本 | `minSdk 26`（Android 8.0） / `targetSdk 35` / JDK 17 / AGP 8.7 |

| 指标 | 数值 |
|---|---|
| Kotlin 代码 | 约 16900 行 / 99 个文件 |
| 模块划分 | `data` / `di` / `domain` / `net` / `pipeline` / `print` / `review` / `ui`（13 个 UI 子模块） |

## 上手使用

1. **设置页填 Key**（右下角）：MinerU API Key + 大模型接入配置。
   大模型配置可保存多套并随时切换；Key 只存在本机 `EncryptedSharedPreferences`，不进日志。
2. **添加错题**（右下角「拍错题」）：拍照 / 相册 / PDF 三选一，见上表。
3. **进度页**：上传 → MinerU 解析 → AI 整理；失败可重试或跳过 AI。
4. **编辑页**：一次识别可能整理出多道题，顶部箭头翻页；带原始识别文本对照，插图可一键剔除。
5. **列表 / 详情**：筛选、搜索、左滑删除可撤销；详情页可复习打卡、标记已掌握。
6. **打印**：右上角打印机进入，勾选题目后生成 A4 PDF。

## 数据与密钥

> **公开安装包不预置任何 API Key。** 首次启动后请在设置页自行填写。
> 这是刻意的设计：`buildConfigField` 注入的字符串会明文落在 DEX 里，
> 任何人下载 APK 都能提取。详见 [`docs/RELEASE.md`](docs/RELEASE.md)。

- **使用者**：安装后进设置页填 MinerU API Key 与大模型接入配置。
  Key 只存本机 `EncryptedSharedPreferences`，不进日志、不随应用上传。
- **本地开发者**：`local.properties` 放真实 Key（已在 `.gitignore`），
  模板见 `local.properties.template`。本地构建（默认）会预填进 APK 省去手填。
- **公开分发**：用 `-PprefillKeys=false` 构建，注入占位符而非真实 Key。
  CI 固定使用该参数，并在上传前扫描 APK 拦截疑似密钥。

**大模型接入以设置页为准**：任何 OpenAI 兼容服务都只需改 Base URL / Key / 模型，
或切换已保存的多套配置。

## CI 打包

`.github/workflows/android.yml` 推送即触发。**不读取任何 Secrets**，
只生成不含密钥的 `local.properties`，以 `-PprefillKeys=false` 编译 `assembleDebug`，
跑单元测试，**再扫描 APK 内所有 dex 拦截疑似密钥**，最后产物命名为
`mistakebook-<分支或tag>-android.apk`：

- push 到 `main` → 产物在 Actions 运行页（Artifacts）下载
- push `v*` tag → 自动创建 Release 并附带 APK

发版流程与检查清单见 [`docs/RELEASE.md`](docs/RELEASE.md)。

日常脚本：

```powershell
# 同步改动到 GitHub（自动 add/commit/push，触发 Actions 打包）
powershell -ExecutionPolicy Bypass -File scripts\sync.ps1 -Message "feat: 首页列表"

# 出手机测试包：打 tag → CI 编译 → 自动挂 Release → 脚本轮询直到可下载
powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Tag "v0.1.0"
```

## 工程记录

这个项目有大量非显然的踩坑，都记在 **[docs/DECISIONS.md](docs/DECISIONS.md)** 里——
包括 `runCatching` 静默吞掉 `OutOfMemoryError`、WebView 硬件 surface 抓图得到全透明位图、
CSS 像素与设备像素差 3 倍、PowerShell 5.1 对无 BOM `.ps1` 按 GBK 解码导致 `git commit` 被静默跳过等。

> 公式渲染那个 bug 值得单说：前三轮都在猜代码，第四轮加上 `adb` 自检通道拿到真机数据后一次定位。
> **渲染类问题不要靠读代码推断，先要可观测数据。**

完整技术规格见 **[PRD.md](PRD.md)**，AI 编码代理的开工指令见 **[AGENTS.md](AGENTS.md)**。

## 本地编译（可选）

本机默认全部由 CI 编译。如需本地：安装 Android Studio（含 SDK）→ 复制
`local.properties.template` 为 `local.properties` 并填 `sdk.dir` → Android Studio 打开本目录。

> 工程路径含中文时 AGP 会拒绝，`gradle.properties` 里已加 `android.overridePathCheck=true` 绕过。
> 本机跑单测会 `ClassNotFoundException`（Gradle test worker 从非 ASCII 路径加载测试类失败），
> 与代码无关；CI 侧是 ASCII 路径故为绿。
