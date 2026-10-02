# 决策记录（AI 代理 & 人工维护）

> PRD 未覆盖的实现决策记录在此。格式：日期 · 决策 · 理由。

## 2026-09-29 初始化

- **CI 为本机唯一编译环境**：本机未安装 Android SDK/Studio，APK 全部由 GitHub Actions 编译；本地仓库仅存放源码与文档。
  - 2026-09-29 补充：本机后来装了 cmdline-tools + platform 35 + build-tools 35，本地也能编译与跑 R8，但仓库仍以 CI 为准。
- **本地不生成 local.properties**：同理，本地无 SDK 时 Gradle 无法运行；用户的 debug 预填 Key 走仓库 Secrets（CI）与未来本机 local.properties 两条路。
- **Release 资产命名为 ASCII**（`mistakebook-<tag>-debug.apk`）：避免中文文件名在下载 URL 中的编码问题。
- **发布 debug APK 而非 release APK**：CI 无签名keystore，debug 包可直接安装测试；正式签名流程留待 P2。
- **git 通道改为 SSH over 443**（`ssh://git@ssh.github.com:443/...`）：本机 `github.com:443` TCP 被阻断（连接重置），`ssh.github.com:443` 与 `api.github.com` 正常。HTTPS 推送不可用，所有脚本改为纯 SSH；Release 轮询走 `api.github.com` REST。
- **仓库变量 API 返回 404**：`PUT /repos/.../actions/variables/{name}` 不可用（疑似 token 权限），改为 workflow 内联默认值（`LLM_BASE_URL` / `LLM_MODEL` fallback），功能不受影响。

## 2026-09-29 工程侧

- **中文工作目录**：仓库位于 `E:\错题本`，AGP 会拒绝非 ASCII 路径，`gradle.properties` 加 `android.overridePathCheck=true`（官方开关，CI 侧为 ASCII 路径无副作用）。
- **PowerShell 脚本带 UTF-8 BOM**：Windows PowerShell 5.1 对无 BOM 的 `.ps1` 按系统 ANSI(GBK) 解码，UTF-8 中文会被解析坏（丢字符串终止符），导致 `sync.ps1` 里 `git commit` 语句被静默跳过、只有 `git add` 生效。所有脚本改为带 BOM 的 UTF-8。
- **`local.properties` 显式读文件**：Gradle 不会把 `local.properties` 注入 project properties，PRD 里 `project.findProperty(key)` 实际取不到值；`app/build.gradle.kts` 改为显式解析该文件，debug 预填与 release 恒空的语义不变。
- **统计在 Kotlin 侧聚合**：Room 对枚举字段做 TypeConverter，`GROUP BY` 投影返回值类型不可靠，改为分别 count 后在 ViewModel 组装，个人题量下完全够用。
- **目录新增 `ui/theme/`**：Compose 主题三件套（Color/Theme/Type），PRD 目录结构未列，属常规补充。

## 2026-09-29 功能侧（用户新需求）

- **大模型接入支持多套配置**：设置页可保存多套「名称 + Base URL + Key + 模型」并在列表里单选切换；整套 JSON 存 `EncryptedSharedPreferences`，活跃配置 id 存 DataStore。PRD 只有单套配置，用户要求「方便修改和切换」。
- **改用第三方中转大模型**（端点已随仓库公开，此处不记录具体域名）：实测该端点 `GET /v1/models` 与带 `image_url` data URL 的 chat/completions 均返回 200，因此新增「识别时附带原图」开关（默认开）：长边压到 1280px、JPEG q80、base64 内联；仅支持文本的服务可在设置页关掉。
- **Base URL 容错**：只填根域名（如 `https://api.example.com`）保存时自动补 `/v1`；已带路径的保持原样。提示词在多题/带图模式下只在 PRD 原文末尾追加说明，原文本体一字未改。
- **批量导入**：相册多选（一次最多 20 张）与 PDF 多页都按「导入组」提交，`capture_tasks` 增加 `groupId` / `orderInGroup` / `groupSize` / `groupTitle` / `sourceType` / `questionIdsJson` / `errorKind`（v1 schema 尚未发布，加字段无迁移成本）。引擎按组顺序单线程执行，进度页显示「第 n / m 张」。
- **一次识别可产出多道题**：LLM 在多题模式下输出 `{"items":[...]}`，`JsonExtractor` 同时兼容单对象与 items 数组；`refinedJson` 存 `DraftBundle`（含多题与降级标记）。编辑页顶部翻页逐题保存，保存 N 题后任务置 DONE，`capture_tasks.questionId` 记首题 id。
- **PDF 入口（用户新需求）**：先判断是否为文本 PDF——自写零依赖抽取器（对象表解析 + FlateDecode + ToUnicode CMap + Tj/TJ 文本算子）；可抽出文字的页直接送大模型（跳过 MinerU），纯图片 PDF 用系统 `PdfRenderer` 按 150 DPI 逐页栅格化后走 MinerU。可指定页码范围，超过 60 页提示分段。
- **噪声图像排除（用户新需求）**：拍照后进自研裁剪页（8 手柄 + 框外压暗 60% + 旋转/重置 + 最小 100px 当量）；识别完成后编辑页把 Markdown 里 `![]()` 引用做成可删除 chip，剔除后同步写回 Markdown。
- **复习规则（PRD 缺 6.4 表）**：按艾宾浩斯 1/2/4/7/15 天五轮。CORRECT 进下一轮，VAGUE 保持本轮且明天再见，WRONG 归零且明天再见；五轮全对 → 已掌握。据此实现详情页打卡与时间线。
- **备份与恢复**：zip = `mistake_book.db` + `files/` 全量。Q+ 用 `MediaStore.Downloads` 落 `Download/错题本/`（无需存储权限），Q 以下落 app 私有目录后靠分享导出；恢复为覆盖式，完成后重启进程生效（Room 单实例无法热切换）。
- **每日提醒**：`WorkManager` 周期任务，首次延迟计算到用户设定的时刻，之后每 24h；通知渠道 `review`；API 33+ 运行时申请 `POST_NOTIFICATIONS`，拒绝则静默跳过。设置页提供「立即提醒一次」便于验收第 7 条。
- **PDF 产出与分享**：先写 app 私有文件 → Q+ 复制进 `MediaStore.Downloads/错题本/`，分享时再复制到 cache 并经 FileProvider 授权，`ACTION_SEND` / `ACTION_VIEW` 均可。

## 2026-09-29 HyperOS 拍照必失败排查（通用教训）

- **所有主线程大图解码必须移到 Dispatchers.IO 并显式 catch (OutOfMemoryError)**：`runCatching` 会静默吞掉 OOM，让内存问题伪装成业务错误（本次表现为裁剪页提示「拍照失败」）。PdfImporter.renderPageSafely 早已按此风格实现，裁剪页这次补齐。
- **CameraX 回调线程不得直接驱动导航**：`takePicture` 的回调派发在 CameraX 的 executor 线程，`navController.navigate()` 与 Compose state 写入都必须切回主线程（Handler(Looper.getMainLooper())）。

- **inSampleSize 的降采样必须保证结果长边 <= 目标值**：while (longEdge / 2 >= maxEdge) 的终止条件会让 4032px 算出 inSampleSize=1（全尺寸解码约 48.8MB）。
- **离开相机页面必须显式 unbind**：Camera2 session / Preview Surface / ImageCapture 的 ImageReader 会继续驻留本进程（小米系 gralloc 计入 RSS），显著推高峰值内存。
- 裁剪预览长边封顶 1600px，inPreferredConfig 保持 ARGB_8888（裁完要送 MinerU 做 OCR，不能用 RGB_565）。
- release 包只裁 Log.d、保留 Log.e/w，保证线上可排查。
## 2026-09-29 裁剪框拖不动 + 公式渲染空白

- **可变对象不能直接喂给 Compose**：`CropRect` 原本是带 `var` 字段的 data class，`applyHandleDrag` 原地改字段，Compose 只认 State 的**引用**变化，因此手柄看得见但拖不动、确认后裁出来的还是默认框。改为不可变 `data class`（`val` + `copy()`）并用 `var rect by remember { mutableStateOf(...) }` 持有，拖拽时整体替换实例。
- **`pointerInput` 的 key 不能放会被拖拽修改的对象**：key 放 `rect` 会导致每次拖动都重建手势检测器。改为放 `viewport` + 位图（只在旋转/布局变化时变），几何换算随之刷新。
- **WebView 必须 `setLayerType(LAYER_TYPE_SOFTWARE)` 才能 `draw(Canvas)` 抓图**：否则内容在硬件 surface 里，`view.draw(canvas)` 抓到的是**全透明位图**——因为 Bitmap 非 null，`InlineMathImage` 不会回退到 LaTeX 源码，表现为公式位置一片空白。同理 `#out` 必须绝对定位到 `(0,0)`，否则量到的 w/h 与位图对不上会错位。
- **WebView 重排/光栅化是异步的**：JS 量完尺寸后要等一两帧（`Choreographer.postFrameCallback`）再抓，否则抓到上一帧空白。
- **`pageLoaded.await()` 必须加超时**：原先直接在 `mutex.withLock()` 内 await，WebView 加载不起来时会永久持有 Mutex，之后**所有**公式渲染一起卡死。改用 `withTimeoutOrNull(8s)`，并把 deferred 改为 var，每次重建 WebView 重新赋值。
- **渲染结果要做空白检测**：抓到的位图若全透明就返回 null，宁可回退成 LaTeX 源码也不给用户一片空白。
- **`evaluateJavascript` 的返回值可能多一层 JSON 编码**：脚本返回字符串时回调收到的是 JSON 字符串字面量（形如 `"{\"w\":1}"`），`parseSize` 先尝试剥外层引号再按对象解析，两种形态都兼容。
- **仓库路径含非 ASCII 字符时，本地跑单测会 `ClassNotFoundException`**：Gradle test worker 从非 ASCII 路径加载测试类失败。已确认与代码无关（干净树同样失败），CI 为 ASCII 路径故为绿。**复现条件：仓库 clone 到含中文/非 ASCII 字符的目录。** 纯逻辑改动可用「把公式改写成等价实现再跑」的方式在本地验证。
## 2026-09-29 Key 门禁误报 / 公式仍空白 / 相册多余页 / 打印默认值不同步

- **Key 门禁对话框必须说真话**：ApiKeyRequiredDialog 原先写死显示「MinerU API Key」，而实际触发条件是 `!mineruConfigured || !llmConfigured`。用户反复去检查一个早就填好的 MinerU Key。改为按实际缺失项分别列出。
- **接入配置读取要能自愈**：loadProfiles() 原来靠一次性的 `seededProfiles` 标志播种。早期版本在 Secrets 为空时会把「空配置」写进 EncryptedSharedPreferences，之后 `parsed.isNotEmpty()` 恒成立、再也不重新播种，导致装了预填 Key 的包也永远过不了门禁。改为：只要已存配置里**一个都不可用**，就注入一份 buildConfig 预填兜底（release 恒为空则不注入），并删掉 `seededProfiles`。
- **WebView 抓图的第二层坑：CSS 像素 ≠ 设备像素**。getBoundingClientRect() 返回 CSS 像素，而 iew.draw(canvas) 画的是设备像素，高分屏差一个 devicePixelRatio（约 3 倍）。必须让 JS 乘 dpr 再返回，否则抠出来的是公式左上角一小块。
- **判断「抓到空白」不能只看 alpha**：WebView 可能先铺一层不透明背景，整张 alpha 都是 255。改为取采样像素里出现最多的颜色当背景色，只要存在明显不同的像素即认为有内容；配合失败重试一次。
- **打印页与设置页共用同一个控件**：重做区高度在设置页是 60~300 步进 10 的 Slider，在打印页却是写死的 60/100/150 三个 chip——设置里调成 180 时打印页「一个都不选中」。已让两处共用 BlankHeightRow。
- **删除多余的「从相册选择」页**：整页只有一个按钮，点下去才是真正的系统选择器，等于凭空多一次点击。选择器直接放进 HomeScreen，Routes.GALLERY 与 GalleryPickScreen.kt 一并删除。
- **备注改为多行**：Question.note 字段本来就有（实体→草稿→编辑页→详情页都通了），但编辑页是单行输入框且排在知识点之后很靠下，不适合「记思路」。改为 3~8 行并加占位提示。
## 2026-09-29 公式渲染（真机 adb 定位，四轮）

**前三轮都在猜，第四轮拿到真机数据一次定位。** 教训：渲染类问题不要靠读代码推断，先要可观测数据。

- **自检通道**（MathRenderer.diagnose() + db shell am start ... --ez probe_math true）：打印 WebView 层类型、页面加载耗时、dpr/视口、JS 原始返回值、位图 alpha 统计与包围盒，并导出 PNG 用 db pull 肉眼确认。四轮返工的根因就是缺这一步。
- **行内公式空白的真正根因**：produceState 写在 InlineTextContent 的 lambda 里，而那段代码在 **Text 的布局过程**中执行——位图异步到位后占位框不重新测量，永远画不出来。表现为「空白」而不是回退源码，是因为拿到的是**非 null 的全透明位图**。修法：把渲染提到 Text 排版之前的顶层 produceState。
- **必须 setLayerType(LAYER_TYPE_SOFTWARE)**：否则内容在硬件 surface 里，iew.draw(canvas) 抓到全透明位图。
- **CSS 像素 ≠ 设备像素**：getBoundingClientRect() 是 CSS px，iew.draw 画的是设备 px，高分屏差一个 dpr，必须让 JS 乘 dpr 再返回。
- **公式尺寸按字号等比缩放**（目标字号 / 渲染字号），不按包围盒高度：后者会把单字母撑得巨大、长分式压扁。JS 需一并返回 s（实际字号）。
- **墨水溢出要留边距**：getBoundingClientRect() 是排版盒，斜体修正/分式横线会略微溢出，Math.round 向下取整就会把最右侧切掉。#out 加 padding-right/bottom。
- **禁止事后裁剪位图**：coerceIn 裁图会让公式「显示一半」。放不下只能**等比缩小**；超宽公式在 HTML 渲染阶段就按位图边长上限缩字号。
- **行高必须够**：Compose 的 inline content 不撑行框，占位框高过行框就会与上下行重叠。行高统一设为 1.9 倍字号，占位框高度 coerceIn 夹在行框内。
- **不要把公式拆成一块块单独占行**：数学推导里公式成串，拆开就没法读（试过 ligned 块分组，更糟）。文字与公式必须在同一文本流里自然折行。
- **-PappIdSuffix=.probe** 构建不同 applicationId 的探针包，与正式包共存调试，绕开签名不一致无法覆盖安装的问题。
- 详情页选项改用 RichText（原先是裸 Text，$...$ 直接显示源码）。
- **备注从编辑页移到详情页**：看题时就能直接记思路，不用进编辑页。
## 2026-09-29 复习功能简化为「是否会了」开关

用户反馈复习记录「鸡肋、标签太多影响观感」。原设计是「会/模糊/不会」三按钮 + 艾宾浩斯 5 轮 + 打卡历史时间线 + 轮次文案，详情页下方一大块。

- **改成单个 Switch「会了吗」**：开 = MASTERED，关 = ACTIVE。整块复习卡片缩成一行。
- **删除**：三按钮打卡、打卡历史时间线、轮次文案（第 N 轮）、
extStageHint()、esetReview()、markMastered()。
- **保留每日提醒**（用户明确要求）：待复习队列仍按 status != MASTERED AND nextReviewAt <= today 取，开关打开时 
extReviewAt 置空即自动退出队列，逻辑天然一致，无需改 ReviewWorker。
- **MasteryStatus 去掉 REVIEWING**：只剩 ACTIVE / MASTERED 两态，与「是否会了」语义对齐；列表页与打印页的状态筛选同步从四档减为「全部/未掌握/已掌握」。
- **不迁移数据库**：eview_logs 表、ReviewLog 实体、ReviewResult 三态枚举、Converters 全部保留，v1 schema 未发布过、无历史数据要迁移，删了反而要改 schema。旧库里的 REVIEWING 字符串由 MasteryStatus.fromNameOrDefault 兜底映射为未掌握。
- **详情页不再订阅打卡历史**：DetailUiState.logs 与 observeLogs 的 Flow 订阅一并去掉，避免为一个不展示的列表付出每帧开销。
- **列表页「待复习」角标与筛选保留**：它和每日提醒是一套语义，去掉会导致提醒点进来没内容。
---

## v0.1.12 —— 打印公式渲染、题目标题、题目附图入库

### 1. PDF 里的 LaTeX 渲染成公式
- **问题**：PDF 用 `StaticLayout` 直接输出文本，`$...$` 原样打出来就是一堆反斜杠和花括号。
- **方案**：`PdfExporter` 接进 `MathRenderer`，把公式渲染成位图后与文字**同一行内基线混排**
  （新增 `MathRichBlock` / `MathPiece` / `MathLine`）。
- **换行策略**：按 `$...$` 切 token；纯文字段按「中文逐字 / 西文按词」切分，公式作为不可分割原子，
  按可用宽度贪心换行。
  - 为什么不逐公式独占一行：推导过程会断得没法读（此前 UI 侧已经否决过同类方案）。
  - 为什么不直接输出源码：用户在纸上看到的是 LaTeX，不是题目。
- **比例常量**：`BASELINE_RATIO = 0.8`（文字基线在行高里的位置）、
  `MATH_BOTTOM_RATIO = 0.18`（公式底边略探出基线，避免整条公式上飘）。
- **缩放**：公式按 `sizePt / rendered.fontPx` 等比缩到与正文字号一致，不改变宽高比。
- **分页**：`drawableHeight` 增加 `MathRichBlock` 分支，按**整行**切——宁可少放几行，
  也不把一行公式劈成两半。
- **线程**：`buildCard` 改为 `suspend`；`MathRenderer.render` 内部自己切 `Dispatchers.Main`，
  在 `Dispatchers.IO` 里调用安全。单题异常用 try/catch 兜住并记日志，不影响整批。
- **渲染失败**：回退成 `$源码$` 纯文字，至少题目原样可读。

### 2. 打印编号
- **问题**：卡片标题用数据库 `id`，删过题之后会跳号（1,2,5,7…），用户没法核对漏了哪题。
- **方案**：`buildCard` 接 `index` 参数，用打印清单里的**连续序号**（1 起）。
  `PrintScreen` 的列表行同步显示 `#N`，与 PDF 上的编号一一对应。

### 3. 题目标题
- `questions` 表加 `title` 字段（未升 schema 版本，v1 未发布过）。
- 提示词新增 `TITLE_AND_ANALYSIS_APPENDIX`：要求 6~20 字短标题、概括考点、不照抄题干。
- `RefinedQuestionDto` / `DraftQuestion` / `QuestionDraft` 逐层加 `title`。
- `Question.displayTitle`：优先 `title`，空则回退到题干首行（剔掉图片引用）取前 40 字。
- 列表页、打印页、删除 snackbar 全部改用 `displayTitle`。
- 编辑页与题目编辑页都加了标题输入框。

### 4. 题目附图入库（原图能重裁 + 打印有题图）
- **根因**：`imageRefs` 原本只活在 `EditUiState` 里，`EditableDraft` 没有对应字段，
  `saveCurrent/saveAll` 调 `toDomainDraft(markdown)` 时直接丢弃。
- **方案**：`questions` 表加 `figurePathsJson`；`EditableDraft` 加 `figurePaths`；
  数据源就是 MinerU 改写后的 Markdown 里的 `![](绝对路径)` 引用。
- **`Question.printImagePath` = `figurePaths.firstOrNull() ?: imagePath`**：
  附图优先，没有附图才回退原图。PDF、列表页、打印页三处统一用它。
- **重新裁剪**：`CropScreen` 加 `recropOnly` 模式——只存图回填，不建识别任务、不检查 API Key。
  `AppContainer` 用 `recropping`(标志) + `recroppedImagePath`(结果) 传递；
  这两个字段**必须是 `mutableStateOf`**，普通 `@Volatile` 不触发重组，编辑页读不到变化。

### 5. 学科：只预置数学 + 通信原理
- `Subject.PRESETS` 只放这两个；`SubjectRepository.seedPresets()` 幂等播种，
  `AppContainer` 的 `init` 里 `appScope.launch` 调一次。
- 提示词的 subject 枚举从九项收敛为 `"数学|通信原理|其他"`，并要求「判断不出就填其他，不要硬猜」。
- 识别不出来时编辑页/题目编辑页给手填输入框（`EditableDraft.manualSubject`），
  手填值优先于识别结果。
- `manualSubject` 独立于 `subjectName`：`subjectName` 是「当前选中的学科名」，
  混在一起会导致下拉选择和手填框互相覆盖。
- 学科筛选移到掌握状态筛选**上面**（先圈范围，再筛状态）；搜索框单独一行置顶居中并加清空按钮。

### 教训
- **公式渲染的「同一行内混排」是唯一可用解**：UI 侧试过四轮（改 CSS、加行高、
  改占位框、换渲染管线）都是治标；根因是「公式是位图、文本是字符」这个结构性差异，
  必须在排版层解决。
- **跨页面回传状态一定要用快照状态**：`@Volatile` 字段 Compose 读不到，
  表现是「保存了但界面不变」，极易误判为逻辑写错。

---

## v0.1.13 —— AI 整理失败、莫名旋转、LaTeX 溢出、涂鸦遮蔽

### 1. AI 整理老是失败（尤其多题）— 三个独立根因

**根因 A：max_tokens 硬编码 4096。**
上一轮为了提高解析详尽度，提示词要求「已知条件 → 关键公式 → 推导 → 结论」。
单题详细解析就已经接近 4000 tokens，多题必然被拦腰截断，
JSON 残缺 → 解析失败 → 降级。**这是多题场景最主要的死因，而且是自造的。**
`ChatRequest.max_tokens` 默认改为 16384，`LlmClient.refineJson` 加 `maxTokens` 参数。

**根因 B：降级阶梯被塞进 `repeat(2)` 的重试里。**
PRD 4.2 要求「带 response_format → 去掉 → 把 JSON 要求写进 prompt」三级降级，
但代码里这三步挤在 2 次重试内：`return@repeat` 每执行一次就消耗一个 attempt。
第一步就把次数用光，后两级根本没机会跑，最后返回笼统的「大模型调用失败」——
用户看到的就是「怎么都整理不出来」。
改写成显式的 `List<LadderStep>`，每级独立一次机会，与瞬时错误重试分离。

**根因 C：`finish_reason` 解析了但从来没用。**
被 `max_tokens` 截断和「模型胡乱输出」在下游完全一样，都落到 BAD_RESPONSE。
现在 `finish_reason == "length"` 直接返回明确错误「题目太多，AI 输出超长被截断」，
不再白白降级重试三次。

**补一层 JSON 修复**（`JsonExtractor`）：
- `repairJson()`：剥围栏、去尾随逗号、全角引号归一。
  真实事故里模型输出常常只差一两个字符，结构其实是对的，直接判失败太浪费。
- `decodeByBraceScan()`：括号配平扫描逐个解最外层 `{}`，覆盖
  `{"1":{题目},"2":{题目}}`（用题号当 key）、`{题目}{题目}`（并列对象没包数组）、
  前面带一句客套话再跟 JSON 这几种标准解析器认不出的形状。

### 2. 照片莫名旋转

**根因**：`ImageImporter` 对 JPEG/PNG 做**裸字节拷贝**，EXIF 方向标记原样留在文件里。
`BitmapFactory` 不认 EXIF，于是同一个文件在不同渲染路径下朝向不一致。
相机还有两条路径：文件路径靠 EXIF、内存路径用 `imageInfo.rotationDegrees` 手动转——
两条路径规则不同，表现为「有时转有时不转」。

**方案**：新增 `ImageNormalizer`，把方向**烘进像素**、EXIF 不再携带方向。
- 导入（`ImageImporter`）与拍照（`captureToFile` 落盘后、`captureInMemory`）统一走它。
- 解码用 `inSampleSize` 限边（`MAX_LONG_EDGE = 2400`），避免 4000×3000 单张 48MB 直接 OOM。
- 归一化失败仍把原文件交出去——增强失败不能连带不让用户拍照。

### 3. 照片发灰、没有对比度增强

`ImageNormalizer.enhanceForOcr`：
- **近灰像素**（纸面、铅笔、黑字）走 Otsu 自适应阈值 + 陡坡拉伸，纸压到接近纯白、字压到接近纯黑。
  阈值取整图直方图的最大类间方差，不用固定 128——偏灰照片和偏亮照片需要不同分界，
  固定阈值会把浅铅笔字吃掉。
- **彩色像素**（红笔、蓝笔）保留色相，只做同曲线亮度缩放。
  全转黑白会把老师的红笔批注一起抹掉，那是错题本里信息量最大的部分。
- 设置页新增「照片增强」开关（`enhancePhotos`），默认开。

### 4. LaTeX 超出手机屏不换行

**根因**：`RichText.InlineRow` 里公式宽度 `w = bw * scale` **从来没有被约束过**。
一个长公式按正文字号渲染出来远超屏幕宽，Compose 既不裁剪也不换行，直接把右半截切掉。

**方案**：`InlineRow` 包 `BoxWithConstraints` 取可用宽度，
每个公式先按宽度等比压缩、再按行高压缩，两道约束顺序不能反。
宁可变小也不能缺笔画。`MathImage`（独立公式）本来就有宽度约束，保持不变。

### 5. 涂鸦遮蔽（识别前抹掉不想要的区域）

用途：红笔批注、旁边的题、草稿——这些内容被识别会污染题干。

- 位置：裁剪页工具栏「涂鸦」开关，白色画笔，支持撤销 / 清空。
- **笔迹存在归一化图片坐标（0..1），不是屏幕像素**。
  存屏幕坐标的话，旋转 90°、改裁剪框、横竖屏切换之后遮罩全部跑到别的位置。
  只有用户主动点「旋转」这一个事件需要显式变换（`MaskStroke.rotatedClockwise`）。
- 涂鸦时隐藏压暗层和手柄：压暗 60% 会让用户看不清自己正要涂什么，手柄也会干扰落笔。
- 保存时 `applyWhiteMask` 用**不透明纯白**而不是半透明——MinerU 会把淡灰当淡字照样识别，
  半透明遮罩等于没遮。
- 笔宽按 `裁剪结果宽 / 屏幕显示宽` 换算，保证遮罩粗细和用户看到的一致。

### 6. 首页布局

- 搜索框移进 TopAppBar 正中，做窄（圆角胶囊、labelSmall、小图标），带清空按钮。
  之前它独占一整行压在筛选条上方，把首屏最宝贵的位置全占了。
- 学科与掌握状态**合并成一排可横滑的标签**。它们都是「切范围」，
  拆两行等于占两倍首屏高度。学科在前（先圈范围），题数统计跟在末尾。

### 7. 难度可改 / 重新识别 / 编辑页预览

- **难度**：详情页星级从只读改为**直接可点**。之前 `DifficultyPicker` 只在编辑页有，
  用户在详情页看到星星以为改不了。`QuestionRepository.setDifficulty`。
- **重新识别**：详情页右上角刷新图标 → 确认弹窗（明确说明会覆盖什么、保留什么）
  → `CaptureTask.replacesQuestionId` 标记 → 编辑页保存时 `overwrite()` 覆盖原题。
  **保留** id / 掌握状态 / 复习排期 / 用户备注，只换内容。
  为什么不复用 `questionId` 字段：它的语义是「本次识别产出的题目」，方向相反。
- **编辑页预览**：`QuestionEditScreen` 顶部补 `RichText` 渲染预览。
  之前只有首次识别后的 `EditScreen` 有，从详情页点编辑进去全是裸 LaTeX 源码。

### 8. 打印默认不带原图

`printIncludeImage` 默认 `true` → `false`（`SettingsSnapshot` / `SettingsStore` /
`PrintViewModel` / `PdfExporter.Options` 四处默认值一起改）。
理由：打印错题本是给手写重做用的，贴照片反而挤掉作答空间。
已存过设置的用户沿用原值，需要时在设置页或打印页打开即可。

### 9. 「会了吗」改「掌握」

文案调整，语义不变（开关开 = MASTERED，关 = ACTIVE）。

### 教训

- **加重提示词必须同步放大 token 预算**。让模型写得更详细的同时不抬 `max_tokens`，
  等于亲手制造「输出被截断 → JSON 残缺 → 解析失败」的故障，而且现象是「AI 老失败」，
  根本联想不到是 token 上限。
- **重试逻辑不要兼职做降级阶梯**。把「换个参数重试」和「换个策略重试」混在一个
  `repeat(n)` 里，n 一旦不够就静默失效，而且失败信息会掩盖真实原因。
- **多级容错解析是划算的**。模型输出的 JSON 差一两个字符的情况远比想象中多，
  花几十行做括号扫描 + 轻量修复，救回的失败比任何模型调优都多。
- **跨页面回传状态一定要用快照状态**：`@Volatile` 字段 Compose 读不到，
  现象是「保存了但界面不变」。
- **约束不能只加一半**。公式渲染在 UI 侧加了宽度约束、PDF 侧没加，
  结果是「手机上好了、打印还是乱的」。同一个排版问题两个渲染路径要一起改。

---

## v0.1.14 —— 错题本分类（数据库首次正式迁移）

### 需求
「不只是学科」——学科回答「这是什么题」，错题本回答「我把它归到哪」。
扫完每道题可以进默认错题本，也可以新建自定义错题本。

### 1. 数据模型
- 新增 `Notebook` 实体（id / name / sortOrder / isDefault / createdAt / updatedAt）。
- `Question` 加 `notebookId: Long?` + 索引。
- **两个字段并存、互不覆盖**：`subjectId` 与 `notebookId` 正交。
  用户想按学科筛就按学科筛，想按「高数竞赛」「期末冲刺」这种自己的规划筛就用错题本。

**为什么 notebookId 可空而不是 NOT NULL**：让「没归类」和「默认错题本」
在数据上可区分。迁移过来的老题目不該被假装成用户主动归过类；
`seedDefault()` 会在启动时把孤儿归入默认本，所以「未归类」只在校验/兜底场景出现。

### 2. 这次必须写 Migration（关键决策）

前几轮加 `figurePathsJson` / `title` 时沿用 version=1，理由是「v1 未发布过」。
**这个前提现在不成立了**：v0.1.0~v0.1.13 已发布，用户手机上有真实错题。

Room 开库时会核对 schema 指纹。**版本号不变但表结构变了，老用户一打开就抛
`IllegalStateException` 直接闪退。** 所以本轮：
- `version = 2`
- 写 `MIGRATION_1_2`：建 `notebooks` 表 + `index_notebooks_sortOrder`
  + `ALTER TABLE questions ADD COLUMN notebookId` + `index_questions_notebookId`
- **不开 `fallbackToDestructiveMigration`**——那会静默清空用户攒下的错题。

迁移 SQL 已与 KSP 生成的 `2.json` 逐字核对一致（列名、类型、可空性、索引全部对齐）。
Room 的 schema 校验比对的是这个，不一致就是「装得上、打开闪退」。

### 3. 三条不变量（NotebookRepository）

1. **任何时候至少存在一个默认错题本**：`seedDefault()` 幂等，
   启动时必跑。已存在同名「默认错题本」但没打 `isDefault` 标记时认它当默认，
   免得凭空冒出两个同名本。
2. **题目永远有归属**：删错题本前先 `moveQuestions` 搬到默认本。
   「删除一个分类」的合理预期是「这些题还在，只是不在这个分类下了」，
   静默连带删掉几十道错题不可接受。
3. **默认错题本不可删除**：它同时是「删本时题目的接盘者」，删了就没有兜底了。

重名自动加后缀（`名称 2`），不报错也不覆盖。

### 4. 交互落点
- **识别后**：`EditScreen` 顶部加 `NotebookPicker`，
  所有草稿默认预选默认错题本——默认必须是「有个去处」，不能是「未归类」。
- **详情页**：`NotebookPicker` 直接改归属，立即落库。
- **题目编辑页**：同一个选择器。
- **首页**：标签行加「错题本」下拉（与学科、掌握状态同一排，横向可滑），
  底部带「管理错题本…」入口。新建/重命名/删除在独立页 `NotebookScreen`。
  新建完直接选中新本并回首页，筛选立刻生效。

### 5. 踩到的坑
- **`combine` 的类型化重载最多 5 个 flow**。首页筛选要合 6 个（status/subject/
  notebook/keyword/page/due），用 vararg 版本会退化成 `Array<Any?>`，类型全丢。
  改成 5 个先合、再 `.combine(page)` 嵌套。
- **`NotebookScreen` 里用了一个叫 `Box0` 的私有函数**只是因为 `Box` 被 import 占了名——
  这是坏味道，应该直接内联 `Column`。记下来提醒后续清理。

### 教训

- **「schema 还没发布所以不用迁移」是有保质期的假设**。
  一旦发出第一个带数据的包，这个假设立刻失效，而后果是所有老用户闪退——
  属于必须在发版前就想到、但很容易在连续迭代中被忘掉的那类决策。
  规则应该反过来：**只要字段会留在用户设备上，就默认需要写迁移**。
- **破坏性操作的默认动作要选「保留数据」的那个**。
  删分类改成「把题目搬走」而不是「连带删除」，成本只是多一次 UPDATE，
  但避免了不可逆的数据损失。




---

## v0.1.15 —— 修 `\n`、重识别跳转、涂鸦裁剪状态、批量渲染、PDF 排版

### 1. 字面量 `\n` 不渲染（自造坑）

提示词里写了「步骤之间用 \n 分隔」，模型就忠实输出 `\` + `n`。
走 JSON 解析那条路会被 `Json` 解成真换行；走降级兜底那条路就是字面量，
界面上直接显示 `\n`。

修法：`JsonExtractor.normalizeBreaks()` 把 `\n` `\t` `\r` 还原成真控制符。

**不能用 `replace("\\n", "\n")` 一刀切**——那会把 LaTeX 里的
`\nabla`、`\neq` 也毁掉。所以只处理「反斜杠 + n/t/r」这三个，
其余反斜杠组合（LaTeX 命令）原样保留。

### 2. 重新识别点了直接回主页

`reRecognize` 成功后调的是 `onBack()`，用户看到「点了没反应，首页多了几道题」。
改成跳进度页（`popUpTo(HOME)`），让用户看着它跑完。

返回值类型从 `(String?) -> Unit` 改成 `sealed ReRecognizeResult`：
前者分不清「成功但没任务」和「失败且无原因」，调用方只能靠猜。

### 3. 编辑页「重新裁剪」消失

`QuestionEditScreen`（详情页进入的那条路径）从来没接过 `onRecrop`，
只有识别后的 `EditScreen` 有。用户从详情页进编辑，自然找不到。
现在两条路径都有，参数一路传到 NavHost。

### 4. 涂鸦与裁剪串不起来（三个独立 bug）

**4a. 退出涂鸦丢笔。** 「完成涂鸦」按钮里 `activeStroke = emptyList()`，
把用户正在画的那一笔直接丢了——「我明明涂了，点完成就没了」。
改成先 commit 再退出。

**4b. 裁剪状态不持久化。** `rect` / `strokes` / `rotation` 原本是
`remember { mutableStateOf }`，离开页面即灭。涂完返回编辑页再点「重新裁剪」，
遮罩全没、裁剪框回默认位置。

新增 `CropSessionStore` + `CropSession`，存 **DataStore**（不是 Room）：
这是一份临时的、以源图片路径为键的编辑会话状态，不是业务数据，
放进 Room 要为一个 UI 草稿建表 + 迁移，代价与收益不成比例。

落盘时机：拖拽 `onDragEnd`、点按钮这些**动作边界**，不在拖拽过程中——
每个 drag event 写 DataStore 会把 IO 打满。
恢复时机：`LaunchedEffect(imagePath, bitmap)`，且**先应用旋转再恢复笔迹**
（笔迹是归一化坐标，旋转会改变图片朝向，顺序反了会错位）。

**4c. 预览与实际送识别的图不一致。** 见「用户可见一致原则」一节。

键的构造：`crop_session_${path.length}_${path.hashCode()}`。
只靠 `hashCode()` 的话两张不同路径的图片撞进同一个键的概率不为零，
撞了就互相覆盖裁剪状态。

### 5. 换 DeepSeek 官方

`local.properties` + `build.gradle.kts` 两处默认值都改
（`https://api.deepseek.com` / `deepseek-chat`）。
`LlmProfile.normalized()` 会自动把裸域名补成 `/v1`，正好是官方推荐路径，无需额外处理。
Key 仍只走 local.properties（已在 .gitignore），不入 git。

### 6. 去掉「立即提醒」

`ReviewScheduler.runNow` 保留实现（PRD 验收第 7 条要靠它手动触发），只删 UI 入口。

### 7. 三个统一的下拉菜单

需求「应该做成三个可以展开的菜单」。之前是「下拉框 + FilterChip」混排，
视觉语言完全不同。新增 `FilterMenus.kt`：学科 / 掌握程度 / 错题本
统一成同一种「图标 + 标签·值 + 计数 + 箭头」按钮，一眼看出是同一族。
菜单内容超过 320dp 就滚动，避免学科/错题本多时撑破屏幕。

### 8. LaTeX 渲染卡顿（性能）

**根因是量级问题，不是常数问题。** 单条路径每渲染一个公式：
2 次 Choreographer 帧等待（每次约 16ms）+ 2 次全画布 draw + 1 张全尺寸位图。
一道题解析里有 8~12 个公式，即 16~24 次帧等待 + N 张位图的分配与 GC。

**解法：批量渲染。** 新增 `MathRenderer.renderAll()` 与 JS 端 `renderBatch()`：
把 N 个公式一次性竖排进同一条「长卷」页面，只等 2 帧、只 draw 一次，
再按各行偏移切图。**帧等待从 O(N) 降到 O(1)**。

配套细节：
- 长卷高度由 JS 返回，Android 侧据此**先把视口撑高再 draw**——
  顺序反了只会拿到第一屏，量出的 sheetRect 也是截断的。
- `BATCH_LIMIT = 12`：一次画 40 个公式的位图开销会触发 GC 抖动，
  比画 4 批 10 个更慢。
- 缓存键与 latex 分开（`Triple(key, latex, display)`）：
  行内 `r:` 前缀的公式在 JS 侧要包 `\begin{aligned}`，
  但缓存键必须用**原始** key，否则命中不了单条路径写入的同一份缓存。
- `RichText` 与 `PdfExporter` 都切到批量。PDF 侧额外加 `prerenderMath()`：
  整题公式一次渲染完，避免在换行计算过程中逐个调。

### 9. PDF 排版（保持 PDF，不换 docx）

本轮修的四个具体问题：
- **重做区压住正文**：标签画在 `top+12`、第一根线在 `top+28`，
  但块高只按用户设的 `blankHeightPt` 给。调到最小值时标签就压到上一段公式上。
  改成标签高度计入块高（`LABEL_RESERVED`），横线只画在标签下方。
- **标题行里 `$\ln(1+t)$` 原样打印**：标题行走 `StaticLayout`，**不做公式渲染**。
  新增 `plainText()` 剥掉 `$...$`。不改成 MathRichBlock 是因为标题行允许折行，
  而知识点里的 `$\int_0^1$` 剥成什么都不如不显示；正文和解析才值得渲染。
- **标题高度写死 16pt**：知识点多时标题折到两行，第二行被切掉。改成按实际行数。
- **公式与文字咬在一起**：基线写死 `BASELINE_RATIO = 0.8`。
  字号 12 / 行高 1.3 时基线落在 15.6pt，而实际 ascent 约 11.3pt，
  公式明显压到下一行。改成用 `TextPaint.fontMetrics.ascent` 实测。
  同时带公式的行高取「行框高」与「公式高乘 1.25」中更大者，
  避免分式被压扁、分子分母糊成一条。

### 教训

- **提示词里写的「转义写法」必须在解析侧还原**。
  让模型输出 `\n` 分隔步骤，模型确实照做了，但只有走 JSON 解析那条路才会被解开。
  两条路径（正常 / 降级）都要擦一遍。教训是：**格式约定不能只在一处实现**。
- **状态放 `remember` 里 = 离开页面即灭**。凡是用户会「做了几步、回头再改」的东西，
  就必须落盘。判断标准很简单：**用户会不会期望它还在？**
- **UI 草稿状态不要进 Room**。它生命周期短、变化频繁、没有查询需求，
  进 Room 就要建表 + 迁移。DataStore 更合适。
- **性能问题先看量级**。渲染慢有很多可能，我一开始想的是「加缓存」「降分辨率」，
  但真正的瓶颈是「每个元素 2 次帧等待」这种 O(N) 的等待。
  改常数省不了 10 倍，改量级可以。
- **写死的比例不如实测的度量**。`BASELINE_RATIO = 0.8` 这种魔法数换字号换行距就失效，
  `fontMetrics.ascent` 是免费的正确答案。

---

## v0.1.16 —— 涂鸦遮罩的三个坐标换算 bug（v0.1.15 只是绕过去了）

v0.1.15 补了裁剪状态持久化，但**没有解决「涂鸦和裁剪对不上」本身**。
用户反馈「裁切的图片根本没管涂鸦的变化，涂鸦容易错位」。
这次把坐标换算逐行核对，找到了三个独立的 bug。

### 1. 笔迹位置错位

笔迹存的是**整图归一化坐标**（横跨整张照片的 0..1），
而绘制目标是**裁剪之后**的位图。正确换算是：

```
cropX = nx * bitmap.width - cropOriginX
```

原来写的是 `nx * croppedWidth` —— **漏了减裁剪原点**。
裁剪框默认居中时偏差不大，越往右偏得越远，用户看到的就是遮罩整体漂移。

### 2. 笔宽细了一个 rect.width() 倍（最致命）

屏幕像素 → 裁剪结果像素的正确比例：

```
scale = cropWidthPx / (rect.width() * referenceWidthPx)
      = bitmap.width  /  referenceWidthPx
```

原来写的是 `brushScreenPx * (cropWidth / referenceWidthPx)`，
等于多乘了一个 `rect.width()`，**笔宽被按裁剪框大小二次打折**。

默认裁剪框 0.88 宽时差别不明显（0.88×），但用户把框收紧到 0.2 时
笔宽只剩五分之一，再经 `scaleLongEdge` 压到 1600px ——
**细到几乎看不见**。这就是「裁切的图片根本没管涂鸦的变化」的直接原因：
不是遮罩没生效，是生效了但细得像根头发丝。

### 3. 旋转笔迹的公式只对正方形成立

```kotlin
// 原来
Offset(1f - it.y, it.x)
```

这个「顺时针 90°」公式隐含假设图片是方形的。照片是 4:3 / 3:4，必须按像素算：

```
原图像素 (px, py) = (nx * W, ny * H)
旋转后像素 = (H - py, px)          // 新的宽是 H
新归一化 = ((H - py) / H, px / W)
```

3:4 照片上偏差可达 0.25 个归一化单位——笔迹会明显跑到别的位置。

### 4. 加「预览」按钮：把送进 AI 的那张图摊开

裁剪页画的是「原图 + 半透明笔迹覆盖层」，
识别吃的是「裁剪 + 涂白后的位图」，两者只要有一处换算不对就对不上。
**与其让用户猜，不如把最终产物摊开。**

新增 `buildPreviewBitmap()`，与 `saveCrop()` **共用同一段裁剪 + 遮罩换算逻辑**。
另写一套的话不一致的 bug 会原样复现——这是这个函数存在的全部意义。

预览不做 `scaleLongEdge`：预览要如实反映遮罩位置，缩放会引入插值误差。

### 教训

- **归一化坐标的旋转变换不是 `(x,y) -> (1-y, x)`**。
  那个公式只对正方形成立。涉及非等比变换时，一律「先还原成像素、变换、再重新归一化」，
  不要在归一化空间里直接套等比公式。
- **两套坐标系混用时，先写下换算式再写代码**。
  本次三个 bug 全是「整图坐标」和「裁剪后坐标」没分清。
  正确做法是在代码注释里写出换算公式（`cropX = nx * W - originX`），
  让人能直接对着检查。
- **比例系数里藏着重复因子**。
  `cropWidth / referenceWidthPx` 看着合理，但 `cropWidth` 本身已含 `rect.width()`，
  分母再除一次就抵消了。**任何一个比例系数都该问一句：分子分母是不是同一套坐标。**
- **「功能没生效」和「功能生效了但看不出来」要分开查**。
  遮罩是生成了的，只是细到看不见。直接怀疑「逻辑没跑」会往错的方向查很久。
- **不确定预览和实际是否一致时，把最终产物给用户看**。
  这比在代码里反复推演坐标可靠得多——用户一眼就能看出对不对。

---

## v0.1.17 —— 旋转改逆时针 + LaTeX 严格约束与自动修复

### 1. 旋转方向

`postRotate(90f)` 是顺时针，改成 `postRotate(-90f)` 逆时针。
理由：手举手机拍纸质题目时，用户心里的动作是「把手机往左转」。

**同步改了笔迹变换**：`rotatedClockwise` → `rotatedCounterClockwise`，
公式从 `(1-y, x)` 换成按像素算的逆时针版本。

位图方向与笔迹方向**必须一致**——不一致就是遮罩相对图片整体镜像，
越转越偏，且不报错。这是「涂鸦错位」最隐蔽的一种成因。

`rotationApplied` 的语义从「角度数（0/90/180/270）」改成「圈数（0..3）」，
`+1` 表示逆时针 90°，与位图方向对齐。

### 2. LaTeX 输出不规范

真机观察到的失败（用户截图）：

| 坏输出 | 现象 | 根因 |
|---|---|---|
| `F\left(\frac{y}{x},\frac{z}{x}\right) = 0` | 整条显示红色源码 | `\left`/`\right` 或花括号未配对，KaTeX 直接放弃整个公式 |
| `F_` | 显示成 `F\_` | 下标后丢了内容，模型截断 |
| `x_{1}^{2}` 偶发失败 | 部分字符渲染异常 | 模型混用 Unicode 符号与 LaTeX |

**双管齐下，不能只靠提示词：**

**A. 提示词严格化**（`LATEX_FORMAT_APPENDIX` 重写，18 条）：
按「分隔符 / 必须配对的结构 / 上下标 / 命令白名单 / 绝对禁止 / 输出前自检」六组组织。
每一条都对应一个观察到的坏输出，不是泛泛而谈。
特别强调「不确定要不要伸缩括号就别用 `\left`，用普通括号」——
普通括号不会因缺配对而让整条公式失败。

**B. 新增 `LatexSanitizer`（自动修复）**：
- `balanceBraces` 补全未闭合花括号
- `balanceDelimiters` 给落单的 `\left` 补 `\right.`
- `dropUnknownCommands` 把 KaTeX 不认的命令**降级为正体文本**（去掉反斜杠）
  而不是删除——删除会改变公式含义，降级只是排版变朴素
- `stripStrayDollar` 清理奇数个 `$`

接在 `MathRenderer.render()` 与 `buildBatchScript()` 两处
（批量路径绕过 `render()`，清洗必须做两次，否则两条路径行为不一致）。

**原则**：只做「确定不改变语义」的修补，不做猜测性补全。
宁可少修，也不要把公式改成另一个意思——那比显示源码更危险。

### 3. 单元测试

新增 `LatexSanitizerTest`，12 个 case 全部来自真机坏输出。
方法名用英文：中文 backtick 方法名在部分 JVM 环境下触发 initializationError。

本地跑不了单测：`ClassNotFoundException` ——
中文路径 `E:\错题本` 导致 classpath 编码问题（v0.1.0 时就确认过，干净树同样失败）。
CI 用 ASCII 路径，会正常执行，由 CI 验证。

### 教训

- **提示词约束 + 运行时修复，两层都要有**。
  提示词能减少 80% 的错误，但剩下 20% 会让用户看到一片红字，
  体验上等于「这个 App 识别不了公式」。运行时修复兜住这一部分。
- **位图变换和与之绑定的数据变换必须成对修改**。
  旋转方向改了、笔迹方向没改，症状是「慢慢偏」而不是「立刻错」，
  极难定位。改一个必须立刻检查还有谁依赖它。
- **「整条公式失败」比「渲染错」更糟**。
  KaTeX 遇到一个非法 token 就放弃整个表达式，
  所以 `\left` 缺配对的代价远大于下标少一个字符。
  提示词里明确写「不确定就别用 `\left`」比事后补救更有效。
- **纯文本的测试方法名在中文 backtick 里有坑**。

### 4. CI 失败暴露的真问题：语义性命令不能降级

第一次提交 CI 红在「Run unit tests」。本地因中文路径跑不了单测，
于是把清洗逻辑改写成等价的 Java 用 javac 跑（绕开 Android 工程与 classpath 问题），
立刻暴露出一个**设计缺陷**：

`\bar` 原本被我收在白名单里（当"横线"宏），测试断言它被降级为字母 `bar`——
但这恰恰是错的。`\bar{x}` 降级成 `barx`，**公式含义被改掉了**，
比显示红色源码危险得多。

修正为**两条分支**：
- 装饰性命令（`\bm`、`\textbf`）：降级为正体文本，只影响排版
- 语义性命令（`\bar`、`\hat`、`\vec`、`\tilde`、`\overline`、`\dot`…）：**直接删除**，
  只保留被修饰的符号本身

判据：**这个命令是否改变符号的数学含义**。改变的一律删，不改变的一律降级。

顺带修掉 `overline`/`underline` 同时出现在两个集合里的问题
（白名单优先级更高，会让删除分支静默失效）。

### 5. 本地单测跑不了单测的绕行方案

中文路径 `E:\错题本` 让 JVM 加载测试类失败（`ClassNotFoundException`，
`SmokeTest` 等**原有**测试同样失败，可证明与新代码无关）。

绕行：把纯逻辑代码改写成**等价的 Java**，用 `javac` + `java` 在
ASCII 临时目录里跑。这样能在提交前验证算法行为，不依赖 Android 工程。
Android 相关的部分仍由 CI 验证。
---

## 2026-09-30 LaTeX 渲染修复（v0.0.4）

### 公式变成「一坨狗屎」的真凶

用户报详情页公式渲染成 `x∈0`、`ight]`。**我最初的诊断是错的**，两次都错：

**第一次猜「JSON 转义吃掉了反斜杠」。** 方向对了一半：模型在 JSON 字符串里写
LaTeX，`\t` `\r` `\n` `\b` `\f` 恰好都是**合法 JSON 转义**，
解析器会把它们变成控制字符。这是个真实且隐蔽的问题（业界几乎所有方案都用
`\\(?![/"\\/bfnrtu])` 这个正则，**按构造就修不了它**——`\t` 本身合法）。

**第二次才找到真凶：v0.0.3 的 `JsonExtractor.normalizeBreaks()`。**
它无条件把字面 `\t` 变制表符、把 `\r` 整段删除：

```kotlin
't' -> { out.append('\t'); i += 2 }   // 毁掉 \to \text \theta \times
'r' -> { i += 2 }                     // 毁掉 \right \rho \rangle
```

讽刺的是，该函数自己的 KDoc 就写着「那会把 LaTeX 里的 `\nabla`、`\neq`
之类命令毁掉」——**然后代码正是这么干的**。

### 教训 1：注释里写了风险，不等于代码避开了风险

`normalizeBreaks` 的注释明确警告过这个坑，代码却原样实现了它。
**注释不是证据，测试才是。** 这个坑存在了多个版本而没被发现，
正因为「看着有注释说明」就默认它处理过了。

### 教训 2：拿到设备上的真实数据，而不是继续推理

我读代码改了 `normalizeBreaks` 后，在真机上 `\to` 修好了、`\right` 没修好。
于是做了件本该一开始就做的事：**把 release 包临时标记为 debuggable，
用 `run-as` 把数据库拉下来看**。

真实数据（控制字符可见化后）：

```
stem: 求极限 $\lim_{x<TAB>o 0}\left[\frac{\ln(1 + x)}{x}ight]^{\frac{1}{e^x - 1}}.$
```

`\right` 的反斜杠**和字母 r 一起消失了**，不是变成控制字符。
信息已经不可逆丢失，靠通用规则救不回来。

而 `capture_tasks.refinedJson` 里模型输出是**正确的双反斜杠**：
`"则 $R \\to 0^{+}$ 时"`。说明损坏 100% 出在 `normalizeBreaks`，与模型无关。

**如果一开始就把数据库拉下来看，能省掉两轮错误诊断。**

### 修复分三层，各管一段

1. **解析前保护**（`LatexEscapes.protectLatexEscapes`）
   只在 JSON 字符串字面量内部，把「后接已知 LaTeX 命令名」的单反斜杠补成双反斜杠。
   信息完整保留，JSON 解析后正好还原。模型正确双写的不受影响。

2. **解析后兜底**（`restoreLatexControlChars`）
   控制字符后面紧跟 ASCII 字母时还原成命令——`\to` 吃掉反斜杠后 `o` 还在。
   换行 LF **不还原**：与公式里的真实换行无法区分，猜错会把好公式改坏。

3. **孤儿定界符还原**（`restoreOrphanedRight`）
   `\right` 被删干净后只剩 `ight`，靠上下文还原：`ight` 不是合法 LaTeX，
   紧跟定界符且前面不是字母时补回 `\right`。同类残留（`imes`→`\times` 等）
   **有意不救**——判据不可靠，宁可漏修也不改坏好公式。

第 3 层只救 `ight`，是为了救回**已经损坏的历史数据**；
真正的修复在第 1 层和不再破坏的 `normalizeBreaks`。

### 教训 3：我的修复里有两个「静默失效」，都是测试逮到的

- `mathOnly=true` 让 `restoreLatexControlChars` 只在 `$...$` 里生效，
  但 `RichText` 传给 `MathRenderer` 的是**剥掉 `$` 之后的公式内部内容**，
  `inMath` 永远是 false —— 修复一次都不触发，**且不报错**。
- `$$...$$` 显示公式下 `$` 开关两次，中间被判成「正文」——同样静默失效。

两者都是**写了、编译过、单测全绿，但在真实链路上是死的**。
只有把「真实数据 → 真实管线」的完整路径写成测试才暴露出来
（`FullRenderPipelineTest`）。

### 教训 4：顺序不能反，而且「修复生效」≠「能正常渲染`

先 `clean` 后修复时：`LatexSanitizer.balanceDelimiters` 面对的还是 `ight]`，
认为少一个 `\right`，补上 `\right.`；随后修复把 `\right]` 补回来，
变成 1 个 `\left` 配 2 个 `\right` —— KaTeX 报错，退化成红色源码。

真机上表现就是「修复看起来成功了，但变成一坨红字」。
`delimitersAreBalancedAfterPairing` 这条测试专门守这个顺序。

### 排版：屏幕与 PDF 必须共用一套规则

用户报：字号忽大忽小、公式被裁切、会溢出屏幕、行间互相重合，**PDF 也一样**。

排查发现屏幕（`RichText`）和打印（`PdfExporter`）**各自写了一套缩放逻辑**，
而且规则不一致：屏幕会等比缩小，PDF 只判断换行**从不缩小**，
所以长公式在纸上直接冲出可打印区。

抽出 `MathLayout`（纯函数，可在 JVM 单测）作为唯一判据，两边共用。
结构性好处：想改排版规则只改一处，不可能再漂移。

### 教训 5：排版取舍要问清楚，不要自己猜

这一轮在「压公式」和「不压公式」之间来回错了两次：

1. 先按固定行高卡公式 → 分式被压小、段内字号不齐（用户报「忽大忽小」）
2. 改成「行高迁就公式」→ 分式全尺寸渲染，一行占掉半屏（用户报「latex 太大」）

用户最终明确定调：**「正常字母和汉字的大小一致是最佳的，
分数或者明显需要多行的适当小一点就可以了」**。

落地为三条常量，全在 `MathLayout`：

- `MATH_LETTER_RATIO = 1.0` —— 字母与正文同大
- `INLINE_MATH_MAX_HEIGHT_RATIO = 2.3` —— 总高度封顶，天生高的整体等比缩小；
  `x\to0`（约 1.3 倍）**完全不受影响**
- `INLINE_LINE_BOX_RATIO = 2.45` —— 行高略大于上限，保证不压行

**写死的教训：排版参数不要凭感觉定，要给出取舍依据并让用户确认。**

### 附带修掉的启动崩溃

真机 logcat 抓到：

```
NullPointerException: kotlin.Lazy.getValue() on a null object reference
    at AppContainer.getNotebookRepository(AppContainer.kt:108)
```

`AppContainer` 的 `init { }` 块在类的中部，它启动的协程引用了**声明在它下面**的
`notebookRepository` 和 `enhancePhotosCache`。Kotlin 按声明顺序初始化，
此时 `by lazy` 的委托字段还是 `null`；协程一旦在构造函数跑完前被调度
到别的线程就炸。表现为**启动即崩溃**，触发概率与数据库打开速度相关
（装包后首次启动必现）。把 `init` 移到类末尾即解决。

这个崩溃和排版改动无关，是被反复 force-stop 触发出来的**既有缺陷**，
真实用户同样会遇到。

### 发布密钥丢过一次

本地发布密钥原本放在 `%TEMP%\opencode\release-signing\`，
**被系统清理掉了**。后果是本地再也无法构建可覆盖安装的包。

- 密钥已迁到持久位置 `<用户主目录>\.android\mistakebook\mistakebook-release.jks`
- 重新生成，并**同步更新了 4 个 GitHub Actions Secrets**
  （GitHub 要求 libsodium sealed-box 加密，用 Python `nacl.public.SealedBox`）
- `signing/expected-cert-sha256.txt` 已换成新指纹

**教训：密钥绝不能放在临时目录，也绝不能只存在于本机。**
本轮多亏 CI 的 Secret 里还有旧密钥，否则整个发布链路直接断掉。

### 新增闸门：发布包不得是 debuggable

`-PenforceDebuggable=true` 是本地排障用的（为了 `run-as` 读数据库比对数据）。
但带 `android:debuggable` 的发布包能被任意工具附加调试、读走用户数据。

安全项不能靠「记得别加」，所以加了三道：

- `scripts\verify-signing.ps1`：发版前本地拦截
- CI 闸门 1b：`aapt2 dump badging` 检测
- `app/build.gradle.kts` 里默认关闭，必须显式传参

两条路径（正常包通过 / debuggable 包拦截）都实测验证过。

## v0.0.7 · 题目解答 AI 对话

原始需求文档在传输中损坏（多处乱码/截断），实施过程中所有自创决策记录于此。
完整计划见 `docs/CHAT_PLAN.md`（不要再丢了）。

### 版本编号：继续 0.0.x 而不是 0.1.14

需求里写的是「当前 0.1.13，改成 0.1.14」，但实际仓库在 **0.0.5**（v0.0.6 已占用了 tag），
而且 `app/build.gradle.kts` 里写明了：`v0.1.0~v0.1.17` 这一批公开 Release 曾携带真实 API Key，
已全部删除，**该编号线视为作废、不再复用**（重用会重新进入那段因密钥泄露而作废的编号空间）。
用户确认后继续当前线：**0.0.7 / versionCode 8**。

### 数据层

- **DB 版本 2 -> 3，`MIGRATION_2_3` 必写**。只加表不升版本号的话 Room 运行时
  比对 schema 指纹发现结构变了却没迁移，直接 `IllegalStateException` 闪退。
  禁止 `fallbackToDestructiveMigration()`——那会静默清空用户数据。
- **`ChatSession` 故意不加到 questions 的外键**，只建索引。题目走软删除，
  级联会连带删掉用户自己攒下的对话；对话是不可逆的思考过程。
- `ChatMessage.questionId` 是外键却没索引 → Room 编译期会警告，补了 `Index("questionId")`，
  否则每次改 questions 都会全表扫 chat_messages。
- 枚举入库一律存 **`name` 而不是 `ordinal`**：ordinal 一调顺序，历史数据全部错位且无从忟觉。
- `MessageStatus` 读不出来时兜底 **FAILED 而不是 DONE**：拿到状态未知的消息时宁可显示成失败让用户重试，
  也不能当成正常完成——后者会让用户以为 AI 答完了。

### 上下斄组装（`ChatContextAssembler`，纯逻辑可单测）

- 预算：最近 8 轮 / 8000 字符。超预算时**从最旧的完整轮次整轮丢弃**——
  只丢半轮（丢了用户的问题却留着它的回答）模型会着一个没有问题的回答继续胡扮。
- **最新一轮无条件保留**，即使超预算。用户刚发的问题被自己发出去的历史挤掉是最难排查的故障。
- 图片**不计入字符预算**（一张 1280px JPEG 的 base64 约 20~40 万字符，真算会让任何带图的对话都发不出去），
  改用**张数上限 2** 兜住请求体大小。这是取舍，不是疏漏。
- **发现并修正的真 bug**：`capImages` 原本**正序**遍历倒数分配，结果是**最旧**的消息先抢充名额，
  用户刚贴的图反被丢掉——而 KDoc 里明写着「保留最近的」。
  **注释写了不等于代码做了。**
- `assemble()` 收下 `systemPrompt` / `questionContext` 却不用，靠调用方自己拼头部——
  那种约定迟早在某次改动里漏掉，且漏了不报错。改为输出自包含，
  并删掉「空历史时绕过头部拼装」的特例（它破坏了这个不变式）。

### 网络层

- **流式必须直接用 OkHttp**：Retrofit 的 `Response<T>` 会把 body 一次性读完，拿不到增量。
  非流式降级仍走 Retrofit（`LlmApi`），那里已有成熟的错误处理。
- **流式专用客户端取消 call timeout**：`HttpFactory` 的 300s 是给「一次性拿完」设的，
  流式下一问一答可能超过 5 分钟，届时 socket 还连着却被强行扭断，表现为「答到一半断了」。
  安全性由 120s 读超时兜底。
- **降级阶梯不在 `repeat(n)` 里**（v0.1.13 事故）。`StreamFallback` 抽成纯函数单测，三条硬规则：
  1. **已吐过内容绝不降级**（重发会让用户看到同一段话出现两遍）；
  2. **鉴权/限流/5xx/取消不降级**（重发一次还是同样的结果，只白烧配额）；
  3. 只有 BAD_RESPONSE / NETWORK / TIMEOUT / UNKNOWN 才降级。
- 服务端忽略 `stream` 直接返 JSON 时**就地解析**，不重新发请求。
- `finish_reason == "length"` 必须单独识别：被 `max_tokens` 截断与「模型胡乱输出」在下游完全一样，
  但处理方式不同（提示换模型/分次问）。
- 对话强制 `responseFormat = null`：开 `json_object` 会把回复强行掰成 JSON，用户看到的是花括号而不是讲解。
  `temperature 0.6 / max_tokens 4096`（不沿用纠错任务的 16384）。

### SSE 解析

- **自写解析器而不引入库**（项目规则禁止新增依赖），且各家 SSE 方言差异大。
- **按「一行 = 一帧」实现，而不是按规范等空行再拼。**
  照规范做的话有个真实坑：**最后一帧会一直卡在缓冲区**，直到对端 EOF 或 `[DONE]` 才吐出来。
  而 chat 流式里每个事件固定就是**一行** `data:`（JSON 无法有意义地跨行），
  遵守规范换来的只有「最后一帧可能延迟甚至丢失」。
- 覆盖的真实方言：省略冒号后空格（`data:{...}`）、CRLF 行尾、keep-alive 注释行、
  不发 `[DONE]`、末帧无 content、**HTTP 200 的流里塞错误体**、异常字段、坏帧不中断后续好帧。
- **`"<w:p[ >]"` 这类字符类必须用 `Regex.find`**：写成 `String.indexOf`（字面量匹配）会**永远匹配不到**。

### 附件管线

- **准备阶段不入库**。`chat_attachments.messageId` 是非空外键，而用户是**先选附件、后发消息**——
  选的那一刻消息还不存在，插进去会撞外键约束。所以这一阶段只在内存里（`PendingAttachment`），
  发送时才落库。
- **拷进应用私有目录**：用户从相册/文件管选的原文件随时可能被删或被移动，
  而对话历史是长期存在的——原文件没了，历史里的附件就渲染不出来。
- **DOCX 零依赖抽文本**：docx 就是 zip，正文在 `word/document.xml`。
  不用 `XmlPullParser`：为了抽几百个字去搭一套 SAX 回调还要处理命吏空间。
- `老 .doc 归 OTHER 而不是 DOCX`：它是二进制格式，零依赖解不开。归成 DOCX 会让 UI 显示「就绪」而模型其实什么都没收到。
- 无扩展名又无 MIME 的文件归 `OTHER`：猜成文本的话用户传了二进制文件，
  界面显示「就绪」而抽出来一堆乱码，模型拿着乱码回答。
- 抽文本入库截断到 4000 字符（`MAX_EXCERPT_CHARS`）。不存全文的原因：表会膨胀，
  而上下斄预算总共才 8000 字符，一条附件吃掉大半就等于把历史对话全挤掉。
- 选文件用 `OpenDocument` 而不是 `GetContent`：后者的 Uri 在进程重启后就读不到了。

### UI

- **消息气泡必须走 `RichText`**，不是裸 `Text`——否则 LaTeX 只显示源码。
- **自动跟随的判据是「不在滚动中」，不是「用户没在拖」**。
  `LazyListState.isScrollInProgress` **分不清**用户拖动和程序化滚动，`animateScrollToItem` 期间它同样是 true。
  拿它当「用户在拖」会自己把自己锁死：一开始自动滚 → true → 判定为用户在拖 → 不再滚。
  这个更笨的规则绝对安全：真在拖时不滚（正确），程序化滚动中也不滚（本应如此）。
- **列表末尾加 1dp 哨兵项**：没有它，消息正好排满时没有可滚余量，结论会恰好卡在屏幕外。
- 丢弃的对话在 UI 顶部插灰色分隔条（不落库），避免用户以为 AI 失忆。
- **流式内容直接节流写库，不在 state 里另存一份**。另存一份就要合并「库里的列表」和「内存里的文本」，
  而合并两个来源正是本项目栽过多次的坑。节流到 500ms 一次后 UI 的消息列表永远只有 Room 一个来源。
- **收尾放 `finally` + `NonCancellable`**：取消时 `CancellationException` 已经抛出去了，
  放在 try 之后的那段代码根本不会执行——表现是「按了停止，缓冲区里最后半句永久丢失，状态卡在生成中」。
- 会话列表时间按**自然日**算（`LocalDate` 相减，不是毫秒差除 86400000）：
  后者在夏令时切换那天会算出 0.958 天取整成 0，「昨天下午的对话」被标成「今天」。
  设备时间被改到未来归「今天」，不显示「-1 天前」。
- 项目没引 `lifecycle-runtime-compose`，用 `collectAsState` 而非 `collectAsStateWithLifecycle`（零新增依赖）。
- 本 foundation 版本的 `snapToItem` 不可用，改 `scrollToItem`（本身就是瞬时的）。

### 入口

按规格只放两处顶栏：详情页 `AutoAwesome`、首页 `Forum`。
不加底部按钮、不加编辑页入口、不加列表卡片入口。

### 本轮擒到的真 bug（全部由单测拖出来）

1. `capImages` 正序遍历 → 保留了最旧的图，与 KDoc 相反。
2. `assemble` 空历史时绕过头部拼装 → 输出不再自包含。
3. `ensureQuestionContext` 的守卫用 `countMessages > 0`，而调用顺序是「先插用户消息」
   → **题目上下文一次都注不进去，且不报任何错**。
4. `collectStream` 收尾不在 `finally` 里 → 按停止时最后半句永久丢失。
5. `buildContext` 整条剔除 FAILED 消息 → 模型看到「问了什么」却看不到「已经答到哪」。
6. `SseLineParser` 用字面量 `indexOf` 匹配字符类。同样的错误在 DOCX 里又一师。
7. DOCX 的 `<w:br/>`/`<w:tab/>` 只在「后面没有 `<w:t>`」时才处理，而后面**总是**有 → 换行与制表一个都没扫到。
8. 「删除消息」实际只把状态标成 CANCELED，而「重试」正是「标记旧消息 + 插入新消息」
   → 用户点了删除却看到消息还在。
9. 点「新对话」会造出两个空会话（列表页建一个、聊天页又建一个）→ 每点一次摆一个孤儿。

### 本轮尚未实现（知情留下）

- **附件的 UI 上传上面图上符**：目前只在输入区显示文件名与大小，没有展开预览图。
- **粘怠单不包截断处理**：裁断时会在消息尾部加一个提示，但没有“继续写」的流式续写（需要上游支持，且要处理半截公式的 `$` 未闭合问题）。
- **语义审校**（查答案与解析是否自洽）仍未实现。本轮只在 system 里要求模型自己核对。

## v0.0.8 · 导出格式三选一（PDF / HTML / DOCX）

原本只能导 PDF。用户要求自由选择格式。

### 最关键的决策：内容一份，版式各按格式

新增 `print/ExportModel.kt`：`ExportCard` / `ExportDoc` / `ExportCardBuilder` / `RichToken` / `tokenizeWithMath`。
`PdfExporter` 原来把「要展示哪些内容」写在自己的 `buildCard` 里，
加上 HTML / DOCX 后若各写一遍，就会出现三份「这道题要不要显示答案」。

结果是用户会发现「导 PDF 有答案、导 DOCX 没有」，而这种不一致**不会报错**、只会让人怀疑自己选错了题。

这正是本项目植过多次的坑（v0.1.12：手机上加了宽度约束、打印侧没加）。所以：
`PdfExporter` 只改**输入**（接 `ExportDoc`），版式代码一行不动；
`RichToken` / `tokenizeWithMath` 从 `PdfExporter` 的 private 移到顶层。
**重写一遍切分器必然出现细微差异**，那会让同一道题在 PDF 里公式正常、在 HTML 里显示成裸的 `$x$`。

### 公式的处理：优先原生，回退位图

用户指出「DOCX 不是也可以渲染 LaTeX 公式」——对的，OMML 就是 Word 的原生公式，
双击能编辑。但它不是现成的：需要一个 **LaTeX → OMML 转换器**。

- `print/LatexToOmml.kt`：手写。覆盖 `\frac` / `\sqrt` / `^` / `_` / 希腊字母 /
  常用算符 / 函数名 / `\left \right`。
- **不支持的一律返回 null**，由 `DocxExporter` 逐个公式回退成内嵌位图（`alt` 里留 LaTeX 原文）。
  而不是让整份文档失败：**OMML 一旦吐出结构错误的 XML，Word 打开时会报「文档已损坏」**——
  整份文档作废，比公式丑严重得多。缓存到位图路径永远可用。

HTML 则走 **MathML**（KaTeX `output: 'mathml'`），而不是内嵌位图：
MathML 是文字，浏览器自带渲染能力（Chrome 109+ / Safari / Firefox），
**公式可选中可搜索**；而内嵌位图会让公式变成选不中的图像、文件大几十倍。
拿不到 MathML 时也逐个公式回退位图。

### docx 手写（零依赖）

docx 就是 zip + XML。最少四份套件：
`[Content_Types].xml` / `_rels/.rels` / `word/document.xml` / `word/_rels/document.xml.rels`，
有图时再加 `word/media/`。

- 项目规则禁止引 Apache POI；而上一轮已经手写过**读** docx（`DocxText`，16 条单测），写侧是同一套格式。
- 图片用 DrawingML（`wp:inline` + `pic:pic`），宽高用 **EMU**（1 px @96dpi = 9525 EMU），
  给错比例会把照片拉变形。
- 图片类型按**魔数**判宛而不看扩展名——扩展名可能是错的，Word 会拒绝加载。
- `content_types.xml` 里的图片 Default **按实际用到的才声明**。

### 共享选项与 MIME

- `PdfExporter.Options` 改为 `typealias Options = ExportOptions`。三种格式各自一份开关的话，
  早晚会只改到其中一种。
- `ExportFormat` 把扩展名 / MIME / 显示名收在一处。分享的 Intent 要 MIME，
  落盘的文件名要扩展名，界面要显示名——**分散在三个文件里时连一个不改那两个就会出现「分享出去的东西打不开」**。
- 原来的分享与打开都写死 `application/pdf`，现改为 `output.format.mimeType`。

### 单测拖出来的真 bug

1. **词分组后白色被当成一个 run 输出**：`x + 1` 的空白被插进去当成字符宽的空格，
   而且会把上下标的基推错。“没收到字符就跳过”、不是「当成一个字符」。
2. **上下标的基取错**：原实现把 `base` 同时当作基和上标内容（`m:sSup` 里 `<m:e>` 与 `<m:sup>` 写了同一个变量）。
3. KDoc 里的 `word/media/*` 内容 `*/` **提前闭合了注释**（第二次承这个坑，第一次是 ChatScreen 里的 `image/*`）。

### 已知的兼容性限制

- OMML 在 Word 桌面版与 WPS 正常显示，但 **Google Docs 与部分手机端阅读器支持不好**，可能显示为空或转成图片。这是选 OMML 的代价，已知。
