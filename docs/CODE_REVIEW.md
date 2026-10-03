# 代码审查报告 — 错题本 App（com.mistakebook）

> 生成日期：2026-02-10 会话
> 方式：静态代码审查（全量读取核心文件 + 模式扫描），未运行/未真机复现。

## 审查范围

Application/Activity/DI、Room 实体与迁移、DAO、Repository、SettingsStore、MinerU/Llm 网络层与流式客户端、RecognitionEngine 流水线、JsonExtractor、Chat 全链路、拍照/裁剪/编辑/详情/进度/设置/打印/PDF 导入、MathRenderer/RichText、备份、复习调度、构建配置与 Manifest。

## 审查轮次

① 正确性（空安全、逻辑、异常、边界、状态、API 误用、崩溃）
② 并发/生命周期（协程、Flow、线程、取消、Context、泄漏、ANR）
③ Android 集成/兼容（Compose、权限、Intent、资源、配置变更、进程死亡、API level、混淆）
④ 复核（安全、性能、去重、剔除误报）

**结论**：未发现必现崩溃或静默数据丢失级别的 P0；P1 7 条 + 附加 1 条边界；P2 15 条。

---

## P0（无）

未发现。注明：备份/恢复依赖重启进程生效（AppContainer 注释已声明），不视为缺陷；release 未开 R8 是有意决策（build.gradle.kts 注释），仅记录不报。

---

## P1（高：功能失效、资源泄漏、竞态、OOM 风险）

### 1. 详情页「编辑」保存时丢弃重新裁剪的新原图与附图改动
- 文件：`app/src/main/java/com/mistakebook/ui/edit/QuestionEditViewModel.kt:161-177`
- 问题：`save()` 里 `question.copy(...)` 未含 `imagePath` 和 `figurePaths`。页面有「重新裁剪」（`setImagePath`，QuestionEditScreen.kt:84 已回填）和增删附图入口，保存时全部丢失，库里仍是旧路径。
- 触发条件：详情页 → 编辑 → 重新裁剪原图（或加/删题目附图）→ 保存 → 原图/附图未变。
- 修复建议：`save()` 的 copy 中补 `imagePath = draft.imagePath` 与 `figurePaths`（注意 `QuestionRepository.update` 用 `question.figurePaths` 写 `figurePathsJson`，需把 `draft.figurePaths` 传进去）。
- 置信度：高

### 2. 「重新识别」覆盖题目后 mineruMarkdown 不更新
- 文件：`app/src/main/java/com/mistakebook/ui/edit/EditViewModel.kt:380-410`
- 问题：`overwrite()` 的 copy 漏了 `mineruMarkdown`（也漏 notebookId）。任务表 markdown 是新的，Question 表存旧 Markdown。
- 触发条件：详情页「重新识别」→ 完成 → 编辑保存（覆盖）→ 详情页「查看原始识别文本」显示旧文本。
- 修复建议：copy 中加 `mineruMarkdown = domain.mineruMarkdown`。
- 置信度：高

### 3. 流式对话取消/异常时 OkHttp Response 未关闭 → 连接泄漏
- 文件：`app/src/main/java/com/mistakebook/net/llm/ChatCompletionStream.kt:209-224`
- 问题：`onResponse` 全程无 `response.close()`。取消路径（`call.cancel()` 打断 `readUtf8Line` → IOException）和错误路径连接必然泄漏，每次「停止」都发生一次。
- 触发条件：对话中点「停止」/页面销毁取消流式请求；多轮后连接池被占满、出现连接泄漏警告。
- 修复建议：`onResponse` 的 finally（或 `readBody` 用 `use`）对 `response.close()` 包 `runCatching`。
- 置信度：高

### 4. PDF 文本抽取的 ToUnicode 字体映射跨文档污染（乱码）
- 文件：`app/src/main/java/com/mistakebook/data/pdf/PdfTextExtractor.kt:56`
- 问题：`fontToUnicode` 是 object 单例可变字段，只增不删、从不清理。新 PDF 的字体对象号与旧文档相同且无自己的 ToUnicode 时，会用上一份的映射解码 → 乱码。
- 触发条件：先后导入两个带嵌字体的 PDF。
- 修复建议：每次 `extractAll`/`isTextPdf` 开头 `fontToUnicode.clear()`（或改局部变量）。
- 置信度：高

### 5. 识别任务已完成（DONE）后点取消仍被改写为 FAILED
- 文件：`app/src/main/java/com/mistakebook/pipeline/RecognitionEngine.kt:57-62`
- 问题：`cancel()` 中 `jobs.remove()` 对已完成任务无操作，但随后的 `taskRepository.fail()` 无条件执行——把刚 finish 成 DONE 的任务覆盖为 FAILED。
- 触发条件：任务刚 DONE、进度页未刷新时点取消；或重复点击取消。
- 修复建议：fail 前查状态，仅非 DONE 才改写；或 DAO 用 `UPDATE ... WHERE status NOT IN ('DONE')`。
- 置信度：中高

### 6. 对话/纠错图片全尺寸解码，高像素照片 OOM 崩溃
- 文件：`app/src/main/java/com/mistakebook/pipeline/LlmClient.kt:167-180` 与 `app/src/main/java/com/mistakebook/data/chat/ChatAttachmentPreparer.kt:104-113`
- 问题：两处直接 `BitmapFactory.decodeFile` 全尺寸解码（12MP ≈ 48MB），无 `inJustDecodeBounds` 采样；`OutOfMemoryError` 非 `Exception`，`runCatching` 接不住，直接崩。
- 触发条件：用高分辨率相册照片发对话附件或带图识别。
- 修复建议：先读 bounds 计算 `inSampleSize`（长边 ~1280–1600）再解码，再按 `longEdgePx` 缩放。
- 置信度：高

### 7. 复习提醒时间修改后永不生效（WorkManager KEEP 策略）
- 文件：`app/src/main/java/com/mistakebook/review/ReviewScheduler.kt:85-89` + `app/src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt:363-368`
- 问题：设置页改时间后调 `schedule()`，但 `enqueueUniquePeriodicWork` 用 `ExistingPeriodicWorkPolicy.KEEP`——已存在任务被保留。首次启动已按默认 20:00 注册，之后改时间永不生效。
- 触发条件：改提醒时间后等待到点。
- 修复建议：改时间前 `cancelUniqueWork`，或改用 `ExistingPeriodicWorkPolicy.UPDATE`（WorkManager 2.8+）。
- 置信度：高

### 8.（P1 边界）通知点击在 App 进程存活时不应用「待复习」筛选
- 文件：`app/src/main/java/com/mistakebook/MainActivity.kt:57-129`
- 问题：PendingIntent 用 `CLEAR_TOP|SINGLE_TOP`，进程存活时走 `onNewIntent`，而 `EXTRA_FILTER_DUE` 只在 `onCreate` 读取——待复习筛选不生效。
- 触发条件：App 在后台（未被杀）时点复习通知。
- 修复建议：覆写 `onNewIntent`，重新读取 extra 并传给 NavHost。
- 置信度：高

---

## P2（低：健壮性/体验/一致性）

### 9. 拍照页/相册入口的 API Key 门禁检查不生效（fire-and-forget）
- 文件：`app/src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt:300-308、154-162`
- 问题：Key 检查协程异步发出不等待结果，`takePhoto`/`importUris` 无条件执行。裁剪页确认时才拦（兜底存在，不符合验收 3 意图）。
- 修复建议：把拍照动作放进检查协程内部、通过后再执行。
- 置信度：高

### 10. MinerU 解压 zip-slip 前缀匹配缺分隔符
- 文件：`app/src/main/java/com/mistakebook/pipeline/MineruClient.kt:303`
- 问题：`startsWith(targetDir.canonicalPath)` 缺 `File.separator` 后缀，条目 `../mineru/<同前缀目录>/x` 可写进相邻任务目录（BackupManager 同款守卫已带分隔符）。
- 修复建议：`startsWith(targetDir.canonicalPath + File.separator)`。
- 置信度：高

### 11. `extractServerMessage` 对嵌套对象值抛异常
- 文件：`app/src/main/java/com/mistakebook/net/HttpFactory.kt:81-84`
- 问题：`element[key]?.jsonPrimitive` 在值是 JsonObject/JsonArray 时抛异常，且不在 runCatching 内。
- 修复建议：先判类型再取 jsonPrimitive，或整段包 runCatching。
- 置信度：高

### 12. 大模型 AUTH 失败后仍额外重试一次
- 文件：`app/src/main/java/com/mistakebook/pipeline/LlmClient.kt:82-95`
- 问题：AUTH break 后仍走「瞬时错误退避重试」再发一次请求（每次 401 多烧一次）。
- 修复建议：`if (failure.error.kind in setOf(AUTH, RATE_LIMIT, CANCELLED)) return`。
- 置信度：高

### 13. 备份/恢复期间数据库访问无并发保护
- 文件：`app/src/main/java/com/mistakebook/di/AppContainer.kt:93-102`
- 问题：close/reopen 未与 database getter 同步；期间其他协程访问已关闭库 → IllegalStateException，任务被误标失败。
- 修复建议：同一把锁；备份前后置全局暂停标志。
- 置信度：中

### 14. 首页「待复习」视图内存过滤只搜题干
- 文件：`app/src/main/java/com/mistakebook/ui/home/HomeViewModel.kt:91-96`
- 问题：dueOnly 时只 `stem.contains(keyword)`，与正常六字段搜索不一致，切到待复习再搜索会漏结果。
- 修复建议：与正常搜索同口径或加 SQL 过滤。
- 置信度：高

### 15. 首页「待复习」计数跨零点不刷新
- 文件：`app/src/main/java/com/mistakebook/ui/home/HomeViewModel.kt:124`
- 问题：`observeDueCount(LocalDate.now())` 参数固定，跨天不更新。
- 修复建议：定时重发射 today 再 flatMapLatest。
- 置信度：高

### 16. 手动录入/批量保存全部草稿无效时静默无操作
- 文件：`app/src/main/java/com/mistakebook/ui/edit/EditViewModel.kt:356-369`
- 问题：所有草稿 stem 为空时 ids 为空，不保存不提示，任务永不 DONE。
- 修复建议：空结果时置错误提示或禁用按钮。
- 置信度：高

### 17. `saveCurrent` 的剩余题数判定可跳错题
- 文件：`app/src/main/java/com/mistakebook/ui/edit/EditViewModel.kt:336-341`
- 问题：`remaining` 与 `index+1` 组合在部分保存场景下指针推进与 savedCount 可能错位。
- 修复建议：以 index 为准推进并 coerceIn。
- 置信度：中

### 18. 拍照页 CameraPreview 切闪光灯触发整次相机重绑
- 文件：`app/src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt:436-465`
- 问题：flashMode 也进重绑 key；快速连点时多个异步 bind 并发，顺序不保证（偶发镜头朝向错乱）。
- 修复建议：flashMode 只改 `imageCapture.flashMode`，不进重绑 key。
- 置信度：中高

### 19. 文本附件强制按 UTF-8 读取
- 文件：`app/src/main/java/com/mistakebook/data/chat/ChatAttachmentPreparer.kt:158`
- 问题：GBK 编码 txt 读成乱码摘要发给模型。
- 修复建议：UTF-8 失败后回退 GBK/GB18030。
- 置信度：高

### 20. 编辑页审计弹窗两处硬编码中文
- 文件：`app/src/main/java/com/mistakebook/ui/edit/AuditSheet.kt:99、114`
- 问题：违反「文案集中在 strings.xml」仓库规则。
- 修复建议：挪进 strings.xml（含占位符版本）。
- 置信度：高

### 21. Chat 页面 `collectAsState` 非 lifecycle-aware
- 文件：`app/src/main/java/com/mistakebook/ui/chat/ChatScreen.kt:140`
- 问题：页面被覆盖时仍持续收集与重组；注释已说明是有意取舍，记为风险。
- 置信度：高

### 22. `security-crypto 1.1.0-alpha06`（EncryptedSharedPreferences）为已弃用 alpha
- 文件：`app/build.gradle.kts:220`
- 问题：部分 Android 12+ 设备 Keystore 异常风险，且迁移成本高。
- 修复建议：评估替代方案；至少加 runCatching 降级兜底。
- 置信度：中

### 23. MathRenderer WebView 永不销毁
- 文件：`app/src/main/java/com/mistakebook/math/MathRenderer.kt:395-428`
- 问题：WebView 以 applicationContext 常驻整个进程。功能正确，记录为进程级资源占用风险。
- 置信度：高

---

## 复核结论（第 4 轮要点）

- 剔除的误报：AppContainer 构造竞态（已修）；BackupManager zip-slip（正确）；CropScreen 解码采样（正确）；Room 迁移 SQL 与实体（逐字段核对一致）；`runCatching` 吞取消（末段有 CANCELLED 检查）；WebView 主线程 Mutex（设计如此）；上传无 Content-Type（OSS 签名约束）。
- 安全面：Key 不落 DEX（release 空串 + 占位符）；日志脱敏（redactHeader + release NONE）✅；FileProvider authority 一致 ✅；无第三方统计/自建服务器 ✅。
- 性能面：公式渲染已批量 + LRU；流式落库已节流；残留为 6/8、3、18 三条。

---

## TodoList

### 审查阶段（已完成）
- [x] 盘点项目结构、读 PRD.md 与构建配置，确定审查范围
- [x] 轮次1：正确性审查（空安全、逻辑、异常、边界、状态、API 误用、崩溃）
- [x] 轮次2：并发/生命周期审查（协程、Flow、线程、取消、Context、泄漏、ANR）
- [x] 轮次3：Android 集成/兼容审查（Compose、权限、Intent、资源、配置变更、进程死亡、API level、混淆）
- [x] 轮次4：复核（安全、性能、去重、剔除误报）
- [x] 汇总输出按 P0>P1>P2 排序的 Bug 清单

### 修复阶段（P1 已完成）
- [x] P1-1 编辑页保存补 imagePath/figurePaths（QuestionEditViewModel.save）
- [x] P1-2 重新识别覆盖补 mineruMarkdown（EditViewModel.overwrite）
- [x] P1-3 ChatCompletionStream 关闭 Response
- [x] P1-4 PdfTextExtractor 字体映射按文档隔离
- [x] P1-5 失败状态更新不覆盖 DONE 任务
- [x] P1-6 LlmClient / ChatAttachmentPreparer 图片采样解码
- [x] P1-7 复习提醒时间变化后重新注册周期任务
- [x] P1-8 MainActivity.onNewIntent 应用待复习筛选
### 本轮已确认修复项
- [x] P2-9 拍照/相册门禁等待 Key 检查完成后再执行
- [x] P2-10 MinerU 解压路径校验补目录分隔符并加回归测试
- [x] P2-11 服务端错误 JSON 按类型安全读取并加回归测试
- [x] P2-12 AUTH/RATE_LIMIT/CANCELLED 不再触发退避重试
- [x] P2-14 待复习筛选的关键词字段与普通搜索保持一致
- [x] P2-15 首页待复习计数在本地日期变化时刷新
- [x] P2-17 按草稿索引追踪保存结果，避免跳题和重复覆盖
- [x] P2-18 闪光灯切换只更新拍照配置，不触发相机重绑
- [x] P2-19 文本附件严格解码 UTF-8，失败时回退 GB18030
- [x] P2-20 审计弹窗中文文案集中到 strings.xml

### 本轮验证
- `assembleDebug` 与 `compileDebugUnitTestKotlin` 通过；指定 Gradle 单测执行时因测试 worker `ClassNotFoundException` 未运行断言。新增测试类已编译，纯文本 UTF-8 与 GB18030 解码结果已直接核对。

### 本轮未列为已确认缺陷
- P2-13 数据库维护并发为中置信度架构风险，需专项验证整个 Room 观察/写入生命周期后再定方案。
- P2-16 saveAll 当前没有调用入口；当前编辑按钮对空题干已禁用。
- P2-21 是显式依赖取舍；P2-22 受 PRD 锁定依赖约束；P2-23 是进程级单例设计。

### 验证方式
- 每条修复后：本地 `.\gradlew assembleDebug` + 相关单测
- 「中/中高」置信度条目：先补复现单测或真机确认再修
