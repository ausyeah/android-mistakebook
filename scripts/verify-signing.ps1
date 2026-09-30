# 核对发布签名是否与仓库记录的证书一致。
#
# ## 为什么需要这个脚本
# Android 拒绝安装签名不同的更新。如果发布密钥被误重新生成，
# 后果是**所有已安装用户都无法升级**——只能卸载重装，数据丢失。
# 而这个问题在发版当天是发现不了的：包能装、能跑，只是下一次装不上。
#
# 所以发版前必须核对一次。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\verify-signing.ps1
#
# 退出码：0 = 一致；1 = 不一致或配置缺失。

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$expectedFile = Join-Path $root 'signing\expected-cert-sha256.txt'
$propsFile = Join-Path $root 'keystore.properties'

function Fail($msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
    exit 1
}

# 期望指纹：取文件里第一行非注释内容
#
# **-Encoding UTF8 不能省**：PowerShell 5.1 的 Get-Content 默认按系统 ANSI
# 代码页（本机是 GBK）解码 UTF-8 文件，实测 22 行会被读成 12 行，
# 过滤后拿到空值，脚本直接崩在 .Trim() 上。
# 同一个坑在 .ps1 本身上表现为「缺少终止符」——所以本文件必须存成带 BOM 的 UTF-8。
if (-not (Test-Path $expectedFile)) { Fail "缺少 $expectedFile" }
$expectedLine = Get-Content $expectedFile -Encoding UTF8 |
    Where-Object { $_ -notmatch '^\s*#' -and $_.Trim() } |
    Select-Object -First 1
if (-not $expectedLine) { Fail "$expectedFile 里没读到指纹" }
$expected = ([string]$expectedLine).Trim().ToUpper()
if (-not $expected) { Fail "$expectedFile 里没读到指纹" }
Write-Host "期望指纹: $expected"

# 本地 keystore 配置
if (-not (Test-Path $propsFile)) {
    Fail "缺少 keystore.properties（该文件已 gitignore，见 keystore.properties.template）"
}
$props = @{}
Get-Content $propsFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$storeFile = $props['storeFile']
$storePass = $props['storePassword']
if (-not $storeFile) { Fail "keystore.properties 里没有 storeFile" }
if (-not $storePass) { Fail "keystore.properties 里没有 storePassword" }

# storeFile 可能是相对路径（相对仓库根）或绝对路径
$storePath = if ([System.IO.Path]::IsPathRooted($storeFile)) {
    $storeFile
} else {
    Join-Path $root $storeFile
}
if (-not (Test-Path $storePath)) { Fail "密钥文件不存在: $storePath" }

# 读出证书指纹
#
# keytool 会往 stderr 写警告（JKS→PKCS12 迁移提示等），
# 而 $ErrorActionPreference='Stop' 会把原生命令的 stderr 直接当致命错误抛出。
# 所以这里临时降级为 Continue，再用 $LASTEXITCODE 判断真实成败。
$prevPref = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$out = & keytool -list -v -keystore $storePath -storepass $storePass 2>&1
$keytoolExit = $LASTEXITCODE
$ErrorActionPreference = $prevPref

if ($keytoolExit -ne 0) {
    Fail "keytool 读取密钥失败（退出码 $keytoolExit）。`n请检查 storeFile 路径与 storePassword 是否正确。"
}
$line = $out | Select-String -Pattern '^\s*SHA256:\s*(\S+)' | Select-Object -First 1
if (-not $line) {
    Fail "无法从密钥读出证书指纹，keytool 输出：`n$($out -join "`n")"
}
$actual = $line.Matches[0].Groups[1].Value.ToUpper()
Write-Host "实际指纹: $actual"

if ($actual -ne $expected) {
    Write-Host ""
    Write-Host "签名不一致，不要发版。" -ForegroundColor Red
    Write-Host ""
    Write-Host "  意味着已安装的用户**无法升级**，只能卸载重装（数据丢失）。"
    Write-Host ""
    Write-Host "  先确认：" -ForegroundColor Yellow
    Write-Host "   1) 是不是误重新生成了密钥？去 GitHub Secrets 的 RELEASE_KEYSTORE_B64 取回原密钥"
    Write-Host "   2) 原密钥真的找不到了？接受「所有用户必须重装」，并同步改 applicationId"
    Write-Host ""
    Write-Host "  确认无误后才改 signing\expected-cert-sha256.txt 的那一行。" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "[OK] 签名一致" -ForegroundColor Green
exit 0
