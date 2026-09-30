# ---------------------------------------------------------------
# Windows 本地零基础设施运行脚本
# 用法：
#   .\scripts\run-local.ps1                 # 默认 8080 端口
#   .\scripts\run-local.ps1 -Port 58080     # 端口被占用时换一个
#   .\scripts\run-local.ps1 -ToolRoot D:\tools   # 指定 JDK / Maven 根目录
# ---------------------------------------------------------------
param(
    [int]    $Port      = 8080,
    [string] $Profile   = $null,
    [string] $Embedding = $null,
    [string] $JavaHome  = $null,
    [string] $MavenHome = $null,
    [string] $MavenSettings = $null,
    [string] $ToolRoot  = $null
)

$ErrorActionPreference = 'Stop'

$PSScriptRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $PSScriptRoot

# ---------- 1) 载入 .env（存在即生效），命令行参数优先级更高 ----------
$envFile = Join-Path $PSScriptRoot '.env'
if (Test-Path $envFile) {
    Get-Content -Encoding UTF8 $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $kv = $line -split '=', 2
            if ($kv.Count -eq 2) {
                [Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process')
            }
        }
    }
}

if ($Profile)   { $env:SPRING_PROFILES_ACTIVE = $Profile }
if ($Embedding) { $env:EMBEDDING_PROVIDER     = $Embedding }
if (-not $env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE = 'local' }
if (-not $env:EMBEDDING_PROVIDER)     { $env:EMBEDDING_PROVIDER     = 'transformers' }

# ---------- 2) 定位 JDK 21 ----------
$candidateRoots = @()
if ($ToolRoot)  { $candidateRoots += $ToolRoot }
if ($env:TOOL_ROOT) { $candidateRoots += $env:TOOL_ROOT }
$candidateRoots += @('E:\school\.tools', 'D:\tools', 'C:\tools')

if (-not $JavaHome) {
    foreach ($root in $candidateRoots) {
        if (-not (Test-Path $root)) { continue }
        # JDK 解压后可能多一层目录（例如 jdk21\jdk-21.0.12.1+1），向下找两层
        $hit = Get-ChildItem -Path $root -Directory -Filter 'jdk*' -Recurse -Depth 2 -ErrorAction SilentlyContinue |
               Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
               Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\java.exe'))) {
            $JavaHome = $hit.FullName
            break
        }
    }
}
if (-not $JavaHome -and $env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $JavaHome = $env:JAVA_HOME
}
if (-not $JavaHome) {
    Write-Error '未找到 JDK 21：请用 -JavaHome 指定，或设置 JAVA_HOME / TOOL_ROOT 环境变量。'
}
$env:JAVA_HOME = $JavaHome
Write-Host "JAVA_HOME          : $JavaHome"

# ---------- 3) 定位 Maven ----------
if (-not $MavenHome) {
    foreach ($root in $candidateRoots) {
        if (-not (Test-Path $root)) { continue }
        $hit = Get-ChildItem -Path (Join-Path $root 'maven') -Directory -Recurse -Depth 1 -ErrorAction SilentlyContinue |
               Where-Object { Test-Path (Join-Path $_.FullName 'bin\mvn.cmd') } |
               Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { $MavenHome = $hit.FullName; break }
    }
}
$mvn = if ($MavenHome) { Join-Path $MavenHome 'bin\mvn.cmd' } else { 'mvn' }
if (-not (Get-Command $mvn -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 Maven：请用 -MavenHome 指定，或确保 mvn 在 PATH 中（当前尝试：$mvn）。"
}
Write-Host "MAVEN_HOME         : $MavenHome"

# Maven settings：显式指定 > 环境变量 MAVEN_SETTINGS > 工具根目录下的 m2/settings.xml。
# 不指定的话 Maven 会回落到 ~/.m2/repository（通常在 C 盘）并走官方 central 源。
if (-not $MavenSettings) { $MavenSettings = $env:MAVEN_SETTINGS }
if (-not $MavenSettings) {
    foreach ($root in $candidateRoots) {
        $guess = Join-Path $root 'm2\settings.xml'
        if (Test-Path $guess) { $MavenSettings = $guess; break }
    }
}
if ($MavenSettings -and -not (Test-Path $MavenSettings)) {
    Write-Error "指定的 Maven settings 不存在：$MavenSettings"
}

# ---------- 4) 打印运行信息 ----------
Write-Host "MAVEN_SETTINGS     : $MavenSettings"
Write-Host "SPRING_PROFILES    : $env:SPRING_PROFILES_ACTIVE"
Write-Host "EMBEDDING_PROVIDER : $env:EMBEDDING_PROVIDER"
if ($env:LLM_BASE_URL) { Write-Host "LLM_BASE_URL       : $env:LLM_BASE_URL" }
if ($env:LLM_MODEL)    { Write-Host "LLM_MODEL          : $env:LLM_MODEL" }
Write-Host "服务启动后访问     : http://localhost:$Port/actuator/health"
Write-Host ''

$mvnArgs = @()
if ($MavenSettings) { $mvnArgs += @('-s', $MavenSettings) }
$mvnArgs += @('spring-boot:run', "-Dspring-boot.run.arguments=--server.port=$Port")
& $mvn @mvnArgs
