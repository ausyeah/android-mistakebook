# 发布规范

> 本文件是**发版前必须逐条过一遍的检查清单**。
> v0.1.0~v0.1.17 因为没有这份规范，把真实 API Key 打进了公开安装包，已全部作废。

## 一、密钥纪律（最重要）

### 铁律

1. **任何提交进仓库的文件都不许出现真实密钥**——包括 PRD、README、注释、示例。
2. **公开分发的安装包不含任何密钥**。使用者首次启动后自己在设置页填。
3. **密钥一旦进过公开仓库或公开产物，一律视为已泄露，必须到服务商后台重置。**
   改代码删掉不算数——历史提交里还在，二进制里也能提取。

### 为什么这么严

`buildConfigField` 注入的字符串会**明文落在 DEX 里**。
任何人下载 APK 后执行 `unzip app.apk && strings classes*.dex | grep sk-` 就能拿到密钥。

历史教训：v0.1.0~v0.1.17 的公开 Release 就是这么把两个真实密钥
（MinerU 与大模型各一）发布出去的。

### 本地开发 vs 公开分发

| 场景 | 构建命令 | 密钥 |
|---|---|---|
| 本地开发（默认） | `.\gradlew assembleDebug` | 从 `local.properties` 读，预填进 APK |
| 公开分发 / CI | `.\gradlew assembleDebug -PprefillKeys=false` | 注入占位符 `REPLACE_IN_SETTINGS` |

`local.properties` 在 `.gitignore` 里，本地存密钥是安全的。
`local.properties.template` 是给使用者复制的模板，**只能放占位符**。

### CI 闸门

`.github/workflows/android.yml` 里的 `Verify no secrets in APK` 步骤会在上传前
扫描所有 dex，检出 `sk-` 开头的长串就**直接失败**。
这道闸门是最后一道防线，不要因为「这次没事」就绕过它。

## 二、发版流程

```powershell
# 0. 【发版前必做】核对签名指纹
#    密钥一旦被误换，已安装的用户就再也升不了级，而这个问题
#    在发版当天是发现不了的：包能装、能跑，只是下一次装不上。
powershell -ExecutionPolicy Bypass -File scripts\verify-signing.ps1

# 1. 本地构建通过（release 走固定签名，与 CI 同一个密钥）
.\gradlew --no-daemon --offline assembleRelease

# 2. 同步到 GitHub
powershell -ExecutionPolicy Bypass -File scripts\sync.ps1 -Message "..."

# 3. 等 CI 绿了再打 tag
git tag v0.0.2
git push ssh://git@ssh.github.com:443/ausyeah/android-mistakebook.git v0.0.2

# 4. 轮询 Release 产物
```

**tag 必须在 CI 绿了之后再打**：tag 推送会触发 Release 构建，
CI 红了还打 tag 会留下一个坏掉的公开版本。

## 三、发布签名（关系到用户能不能升级）

### 为什么必须是固定密钥

每个 APK 都要有数字签名，Android 用它识别「这个 app 是谁写的」，
并且**拒绝安装签名不一致的更新**。这是防替换的安全机制。

用 `assembleDebug` 出包会踩这个坑：debug 构建用
`~/.android/debug.keystore`，而 **GitHub runner 是一次性 VM，
keystore 用完即弃，每次构建签名都不一样**。结果不是「装不上」，
而是**永远装不上更新**——每个版本都得卸载重装，用户数据一起丢。

### 现在的做法

| 环节 | 配置 |
|---|---|
| 密钥本体 | 只以 base64 存进 Secret `RELEASE_KEYSTORE_B64`，仓库里**永远没有**密钥文件 |
| 口令 / alias | `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` / `RELEASE_KEY_ALIAS` |
| 本地 | 根目录 `keystore.properties`（已 gitignore），指向同一份密钥 |
| CI | `Restore release keystore` 步骤从 Secrets 还原出 `keystore.properties` |
| 产物 | `assembleRelease`，`debuggable=false` |

### 密钥丢了会怎样

- **只要 GitHub Secrets 还在**，随时可以重新出一个包覆盖安装，用户无感
- **Secrets 和本地备份都没了**：签名密钥无法找回（这是设计如此），
  那个应用 ID 就永久废弃了，只能改 `applicationId` 重新开始。
  所以 `mistakebook-release.jks` 必须至少留一份离线备份

### 密钥文件放哪

发布密钥**不进仓库**，由维护者自行保管在仓库之外：

- `mistakebook-release.jks`（密钥本体）
- 对应的口令与 alias

⚠️ **务必离线备份一份**。GitHub Secrets 里的副本虽然够用来继续发版，
但 Secrets 一旦丢失、或仓库被误删除，就再也找不回来了——
而签名密钥无法「重置」，那个应用 ID 只能作废重来。

### 「签名一致」是靠检查保证的，不是靠记性

三道闸门，任何一道都能拦住密钥被误换：

| 位置 | 检查 | 不一致时 |
|---|---|---|
| 发版前（本地） | `scripts\verify-signing.ps1` 对比 `signing\expected-cert-sha256.txt` | 退出码非 0，打印排查指引 |
| CI 构建中 | `Verify APK is signed`（只验证有签名） | 构建失败 |
| CI 构建中 | `Verify signer matches recorded fingerprint` | **在维护者的 main 分支上构建失败**；fork 降级为 warning |

证书指纹记在 `signing/expected-cert-sha256.txt`。它是公开信息
（每个 APK 里都印着），写进仓库没有安全顾虑。

> 改这个文件是**重大操作**，只在两种情况下改：
> 首次建立发布签名，或确认旧密钥彻底丢失且已接受「所有用户必须重装」
> ——后者还应当一并改 `applicationId`，避免新旧包互相覆盖出错。

### 首次切换到发布签名的代价

从 debug 签名切到发布签名，**第一次必须卸载**（签名变了）。
用户手机里现有数据靠 App 内「数据管理 → 备份」导出 zip 再恢复。

从 v0.0.2 起，往后每次都是普通覆盖升级。

### 关于 R8

`release` 的 `isMinifyEnabled` 目前是 `false`——原因与验证方法见
[第九节](#九还没做的)。

## 四、版本号规则

- 格式 `MAJOR.MINOR.PATCH`，与 `app/build.gradle.kts` 的 `versionName` 保持一致
- 破坏性兼容变更才动 MAJOR；新增功能动 MINOR；修复动 PATCH
- **`versionCode` 必须单调递增**，与 `versionName` 独立
- 同一版本号**不复用**：作废的版本号视为永久占用，避免用户装到两个「同名不同包」

### 唯一例外：完全不可用的首个版本

如果一个版本**根本跑不起来**（用户连主流程都进不去，等同于没有产生任何有效数据），
那么同号重新发布是合理的，而且比发布一个已知坏掉的版本号更好——
没人会主动去装一个「装上就进不去」的包。

`v0.0.1` 就是这种情况：公开包不预置密钥，而设置页的 MinerU Key 输入框
因为 `settings` Flow 不随 SharedPreferences 写入重发，导致**一个字符都打不进去**。
用户进不去设置、也拍不了题，应用等于废的。

这种替换必须做到：

1. **`versionCode` 递增**，否则手机上已装的同 versionCode 包无法覆盖安装，
   用户永远拿不到修复版
2. 删掉旧的 tag 与 Release，让下载页只留下可用的那一个
3. 在 `docs/DECISIONS.md` 记录替换原因

反过来，如果旧版本**能正常使用、并且用户可能已经录入了数据**，
那就绝不能同号替换——用户装新包时 Room 版本号对不上，迁移路径也未经验证，
轻则数据丢失，重则闪退。这种情况老老实实发新版本号。

## 五、产物命名

```
mistakebook-<version>-android.apk
```

不用 `-debug` 后缀。公开分发的就是唯一产物，
带 `debug` 字样会让用户以为是测试版而不敢用。

## 五、Release 说明必须包含

1. **首次配置步骤**（在哪填 Key、Key 从哪来）
2. **「本安装包不预置任何 API Key」的明确声明**
3. 如果有旧版本曾泄露密钥，提示用户去重置

## 七、公开前的仓库检查清单

仓库要从 private 改成 public 之前，逐条确认：

- [ ] `git grep -I -E 'sk-[A-Za-z0-9]{20,}'` 无结果
- [ ] `local.properties` 未被 git 跟踪（`git ls-files` 里查不到）
- [ ] `local.properties.template` 里只有占位符
- [ ] **`keystore.properties` 未被 git 跟踪**（`git check-ignore keystore.properties` 有输出）
- [ ] `git ls-files` 里没有任何 `*.jks` / `*.keystore` / `*.pem`
- [ ] `assembleRelease` 出的包，扫描所有 dex 无 `sk-` 长串
- [ ] **历史提交里也没有密钥**（`git log --all` 逐个 grep）
      —— 这一点最容易漏，改当前文件不等于清掉了历史
- [ ] PRD / README / 文档里没有真实密钥
- [ ] 旧 Release 产物已删除（它们本身就含密钥，删了才等于没发布过）

## 八、密钥泄露后的处置

1. **立即到服务商后台重置密钥**（先做这步，别等）
2. 删除受影响的 Release 产物
3. 用 `git filter-repo` 或重建历史清除提交中的密钥
4. 排查调用日志，看是否有异常调用

> **发布签名密钥泄露**是另一回事、也更麻烦：签名密钥一旦被别人拿到，
> 他们可以伪造出「看起来是你的 app」的安装包，用户无从分辨。
> 唯一的处置是改 `applicationId`（或用 `keytool` 重新生成密钥），
> 因为密钥本身无法「重置」。

## 九、还没做的

- [ ] **开启 R8**：`release` 的 `isMinifyEnabled` 目前是 `false`。
      `proguard-rules.pro` 里已备好 kotlinx.serialization / Retrofit 的 keep 规则，
      但**没在真机验证过**。混淆问题只在运行时暴露（编译期一切正常），
      验证前不开。验证方法：装上 release 包，跑一遍「拍照 → 识别 → 编辑 → 打印」全流程。
- [ ] 打包 woff2/woff 字体，消除 KaTeX 首次渲染的失败请求（见 `THIRD_PARTY_LICENSES.md`）
