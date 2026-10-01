# 题目解答 AI 对话 —— 实施计划

> 本文件是**唯一权威计划**。原始需求文档在传输中损坏（多处乱码/截断），
> 下面每一处「规格缺失」都标注了我采用的假设，**假设已写进对应代码的 KDoc**，
> 后续如果拿到完整原文需要回填核对。

---

## 0. 已确认的基建（不用重新调研）

| 能力 | 位置 | 状态 |
|---|---|---|
| Markdown 解析 | `markdown/MarkdownModel.kt` | ✅ 已就绪 |
| LaTeX 渲染 | `math/MathRenderer.kt` | ✅ 已就绪（`RenderedMath(bitmap, fontPx)`） |
| 富文本渲染 | `ui/common/RichText.kt` | ✅ **消息气泡必须走它**，不能裸 Text |
| 多模态 DTO | `net/llm/LlmDto.kt` | ✅ `ChatContentPart` / `TYPE_TEXT` / `TYPE_IMAGE` 已就绪 |
| `ChatRequest` | 同上 | ✅ 已有 `stream` / `max_tokens` |
| HTTP | `net/HttpFactory.kt` | ✅ read 120s / call 300s、`redactHeader("Authorization")` 已配 |
| 错误模型 | `net/ApiResult.kt` | ✅ 网络层一律包 `ApiResult`，**禁止把异常抛到 UI** |
| PDF 抽文本 | `data/pdf/PdfTextExtractor.kt` | ✅ 附件 PDF 复用它 |
| ViewModel 工厂 | `ui/common/ContainerViewModel.kt` | ✅ `containerViewModel(container) { XxxViewModel(it) }` |
| 容器 | `di/AppContainer.kt` | ➕ 加 `chatRepository` / `chatCompletionStream` 两个 lazy |
| 数据库 | DB `version = 3` | ✅ 步骤 1 已完成（`MIGRATION_2_3`，`schemas/3.json` 已生成） |
| 权限 | manifest | ✅ **本功能零新增 manifest 条目**（`INTERNET` 已有） |

---

## 1. 已锁定的产品决策（不再讨论）

1. **流式优先 + 自动降级**。端点不支持 `stream` 时退回一次性返回，**UI 行为完全一致**。
2. **纯单机，无自建后端。附件不做真上传**：
   - 图片 → 压缩后 base64 data URL 走 `image_url`
   - PDF/TXT/MD/DOCX → 本地抽文本拼进 prompt
3. **每题一个固定会话**（`chat_sessions.questionId`）+ 独立自由会话（`questionId = null`）。
   从题目页进入时**复用该题已有会话**，没有则新建。
4. **入口只有两处**：详情页 TopAppBar、首页 TopAppBar。**不加底部按钮 / 编辑页入口 / 卡片入口。**
5. **`responseFormat` 必须为 `null`**——对话绝不能开 `json_object`。
6. **`temperature = 0.6`、`max_tokens = 4096`**（不沿用纠错任务的 16384）。
7. **首问预填但不自动发送**。预填「请讲解这道题，给出详细解题步骤、答案和易错点。」，
   光标定位，**由用户点发送**。自动发会白烧 token，且用户常想改问法。
8. **上下文策略**：system + 题目上下文（只注入一次，`injected=true`）+ 最近 8 轮 / 8000 字符预算。
   超预算时**从最旧的完整轮次整轮丢弃**；永远保留 system + 题目上下文 + 最近 K 轮；
   被丢弃时插一条 `role=SYSTEM` 灰色分隔条「已省略 N 条早期对话」。
9. **默认不内联题目原图**给模型（体积大），只注入题干/选项/答案/解析/学科/知识点/难度/错因。
   原图由既有 `attachOriginalImage` 开关控制。

---

## 2. 步骤与状态

| 步骤 | 内容 | 状态 |
|---|---|---|
| 1 | 数据层：三实体 + DAO + 转换器 + `MIGRATION_2_3` | ✅ 完成（CI #77 绿） |
| 2 | 仓储层 `ChatRepository` + 上下文组装 | ⬜ |
| 3 | 流式客户端 `ChatCompletionStream`（SSE + 降级） | ⬜ |
| 4 | `ChatViewModel` / `ChatListViewModel`（单一 StateFlow） | ⬜ |
| 5 | 附件管线（选文件 → 私有目录 → 图片压缩 / 文本抽取） | ⬜ |
| 6 | 对话页 `ChatScreen`（消息流 / 输入区 / 停止 / 重试 / 复制） | ⬜ |
| 7 | 滚动跟随 `AutoScrollCoordinator` | ⬜ |
| 8 | 会话列表页 + 两处入口接线 | ⬜ |

---

## 3. 规格缺失处与采用的假设

原始文档以下位置是乱码或截断。**这些假设一旦与真实意图不符，改动面很小**（都在对应文件里）。

### 3.1 `ChatAttachment` 实体字段（原表整行乱码）

按步骤 3 散文反推，已落在实体 KDoc 里：
`sizeBytes` / `widthPx` / `heightPx` / `textExcerpt` / `extractedChars` / `errorMessage` / `createdAt`。
其中 `textExcerpt` **截断到 2000 字符**入库（`MAX_EXCERPT_CHARS`）——
长文档全文入库既膨胀又吃掉上下文预算。

### 3.2 步骤 2 SSE 实现（原段乱码）

采用标准 SSE 语义：
- 请求带 `stream = true`，`Accept: text/event-stream`
- 逐行读 `response.body!!.source()`；`data:` 累加；`data: [DONE]` 结束；空行分隔事件
- **`finish_reason == "length"` 必须识别**：它表示被 `max_tokens` 截断，
  与「模型胡乱输出」在下游完全一样
- **降级阶梯不能塞进 `repeat(n)`**（v0.1.13 事故），要独立于重试
- 非流式降级：再发一次 `stream = false`，走普通 `chatCompletions`

### 3.3 步骤 5 滚动跟随（原段乱码）

抽出独立组件 `AutoScrollCoordinator`，两条路并存：
1. 用户手指正在拖 → **绝不抢滚动**
2. 用户已离底超过阈值 → 追加内容时平滑回底
流式追加用 `snapTo`（瞬时无动画，帧到更稳），「回到底部」按钮用带动画的 `animateScrollToItem`。

哨兵项：列表末尾追加 1dp 空 item，保证**永远有可滚余量**——
否则「消息到底了却看不到结论」是聊天页最典型的坑。

### 3.4 步骤 6 渲染节流（原段乱码）

**不做**（原文明确「本期不做」）：按块切分文本、分段渲染。

本期采用「整条回复攒完再渲染一次」。理由与风险：
- RichText 里的 WebView + `produceState` 在**每帧重组**下会反复触发批量渲染
- 一次流式回复可产生上百次重组 × 每组 12 个公式的往返 → 明显卡顿（实测口径 ~80s）
- **先保证正确，再谈分段**：分段渲染必须等「半截公式 `$` 未闭合」有明确处理办法

---

## 4. 必须遵守的既有坑（`docs/DECISIONS.md` 结论）

**公式渲染**
- WebView 必须 `setLayerType(LAYER_TYPE_SOFTWARE)`，否则 `view.draw(canvas)` 抓到全透明位图
- JS 的 `getBoundingClientRect()` 是 **CSS 像素**，`view.draw()` 画的是**设备像素**，必须乘 `devicePixelRatio`
- `evaluateJavascript` 返回的是 **JSON 字符串字面量**，解析要剥一层
- `produceState` **绝不能写在 `InlineTextContent` 的 lambda 里**
- 公式尺寸按**字号等比缩放**，不按包围盒高度
- **禁止事后裁剪位图**，放不下只能等比缩小
- **同一个排版问题必须在 UI 与 PDF 两条渲染路径上一起改**（v0.1.12 教训）

**数据库**
- 加表/加字段**必须写 Migration 并升版本号**，禁止 `fallbackToDestructiveMigration`

**跨页面状态**
- 需要跨页面回传的状态**必须是 `mutableStateOf` 快照状态**，普通 `@Volatile` Compose 读不到

**网络**
- 降级阶梯**不能塞进 `repeat(n)`** 的重试里
- 必须识别 `finish_reason == "length"`

**协议（本轮新增，最新的一条）**
- JS 与 Kotlin 之间的字段名**必须集中定义 + 用测试钉住**。
  `fs` 被读成 `f`、`err` 被读成 `e`，两次都因为「两边各写各的」而静默失效。
  现已收敛到 `BatchProtocol` + 双向自检测试。

---

## 5. 每步完成标准

1. `.\gradlew assembleDebug` 通过
2. 单元测试全绿（纯逻辑放 `app/src/test`，可 JVM 跑）
3. 无密钥/个人信息进入暂存区
4. `powershell -ExecutionPolicy Bypass -File scripts\sync.ps1 -Message "..."`
5. 自创决策追加到 `docs/DECISIONS.md` 的 `## v0.0.6` 段（**只追加不改写**）
