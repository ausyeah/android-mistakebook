# TODO — v0.1.15 / v0.1.16

## P0 阻断性
- [x] 1. 字面量 `\n` 不渲染 —— `JsonExtractor.normalizeBreaks()`，且不能一刀切（会毁掉 `\nabla`）
- [x] 2. 重新识别点了直接回主页 —— 改跳进度页；返回值改 sealed `ReRecognizeResult`
- [x] 3. 编辑页「重新裁剪」消失 —— `QuestionEditScreen` 从没接过 `onRecrop`
- [x] 4a. 退出涂鸦丢笔 —— 先 commit 再退出
- [x] 4b. 裁剪状态不持久化 —— `CropSessionStore` 存 DataStore
- [x] 4c. 涂鸦与裁剪错位 —— **三个坐标换算 bug**（见下）

### 4c 的三个真 bug（v0.1.16 修复）
1. **笔迹位置错位**：笔迹存整图归一化坐标，却乘裁剪后位图的宽高。漏了减裁剪原点。
2. **笔宽细了 `rect.width()` 倍**：`brushScreenPx * (cropWidth / referenceWidthPx)`，
   应为 `brushScreenPx * (bitmap.width / referenceWidthPx)`。
   裁剪框收紧到 0.2 时笔宽只剩 1/5，再经 `scaleLongEdge` 压到 1600px → 几乎看不见。
   **这就是「裁切的图片根本没管涂鸦的变化」的真正原因。**
3. **旋转笔迹公式只对正方形成立**：`y'=1-y, x'=x` 在 4:3 照片上偏差可达 0.25 个归一化单位。
   必须按像素算 `(H-py, px)` 再按新的宽高重新归一化。

另外加了「预览」按钮：把**真正送去 MinerU 的那张图**弹出来给用户看。
预览走 `buildPreviewBitmap`，与 `saveCrop` **共用同一段换算逻辑**——
另写一套的话不一致的 bug 会原样复现。

## P1 体验
- [x] 5. 换 DeepSeek 官方 `https://api.deepseek.com` / `deepseek-flash`
- [x] 6. 去掉设置页「立即提醒」（`ReviewScheduler.runNow` 实现保留，PRD 验收 7 要用）
- [x] 7. 三个统一样式的下拉菜单：学科 / 掌握程度 / 错题本
- [x] 8. LaTeX 渲染卡顿 —— `renderAll` 批量渲染，帧等待 O(N) → O(1)

## P2 打印
- [x] 9. PDF 排版四项修正（保持 PDF，不换 docx）
      - 9a. 重做区压正文（标签高度计入块高）
      - 9b. 标题行 `$\ln(1+t)$` 原样打印（`plainText()` 剥掉）
      - 9c. 公式基线不稳（改用 `fontMetrics.ascent` 实测）
      - 9d. 标题高度写死 16pt 导致第二行被切
- [x] 10. 回答 docx 问题 —— 结论是 PDF 优先，docx 更差

## 待用户验证（v0.1.16）
- [ ] 涂鸦 → 预览 → 遮罩位置是否与涂抹处一致
- [ ] 裁剪框收紧到很小再涂，遮罩是否仍清晰可见
- [ ] 涂鸦后点旋转 90°，遮罩是否仍贴合
- [ ] 涂鸦 → 确认 → 编辑页原图 → 再点重新裁剪，遮罩是否还在
- [ ] 批量渲染后长解析的题是否还卡

## 后续候选（未开始）
- PDF 排版仍偏朴素：选项与解析的段落节奏、图注位置、跨页时题卡不被拆散
- 首页三个下拉在极窄屏（如 320dp 宽）下的文字截断表现
