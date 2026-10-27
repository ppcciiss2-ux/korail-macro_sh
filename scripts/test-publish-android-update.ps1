# publish-android-update.ps1 이 정해진 순서로 움직이고, 문제가 있으면 JSON 을 바꾸기 전에 멈추는지 확인한다.
# 임시 폴더에 저장소를 흉내 내고 gh·Invoke-WebRequest·gradlew·apksigner 를 가짜로 바꿔 돌린다. GitHub 에는 아무것도 쓰지 않는다.
# 실행: ./scripts/test-publish-android-update.ps1  (모두 통과하면 종료 코드 0)
$ErrorActionPreference = 'Stop'
$signer = '686360867c47cefa18a963025621c9abc97933aedbdd11cafdfb11553f7cac65'
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('korail-publish-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$repo = Join-Path $sandbox 'repo'
$fakeDir = Join-Path $sandbox 'fake'
$log = Join-Path $fakeDir 'calls.log'
$savedEnv = @{ ANDROID_HOME = $env:ANDROID_HOME; ANDROID_SDK_ROOT = $env:ANDROID_SDK_ROOT }

function Write-Text([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}
function Add-Call([string]$Name) { Add-Content -LiteralPath $log -Value $Name -Encoding ASCII }
function Invoke-SandboxGit {
    $ErrorActionPreference = 'Continue'
    $output = & git -C $repo @args 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "git $args 실패`n$($output -join "`n")" }
}

# --- 가짜 GitHub ---
$script:Fake = @{}
function gh {
    $line = @($args) -join ' '
    $global:LASTEXITCODE = 0
    switch -Regex ($line) {
        '^repo view \S+ --json visibility' { Add-Call 'visibility'; return $Fake.Visibility }
        '^api repos/\S+/contents/android-update\.json --jq \.content$' {
            Add-Call 'published-version'
            if (-not $Fake.Published) { $global:LASTEXITCODE = 1; return 'gh: Not Found (HTTP 404)' }
            return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Fake.Published))
        }
        '^api repos/\S+/contents/android-update\.json --jq \.sha$' {
            Add-Call 'json-sha'
            if (-not $Fake.Published) { $global:LASTEXITCODE = 1; return 'gh: Not Found (HTTP 404)' }
            return 'oldsha'
        }
        '^release view ' { Add-Call 'release-exists'; if (-not $Fake.ReleaseExists) { $global:LASTEXITCODE = 1; return 'release not found' }; return '{}' }
        '^release create ' { Add-Call 'upload'; $Fake.Uploaded = $args[3]; return 'ok' }
        '^api -X PUT ' {
            Add-Call 'json-update'
            $content = @($args) | Where-Object { $_ -like 'content=*' } | ForEach-Object { $_.Substring(8) }
            $Fake.Written = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($content)) | ConvertFrom-Json
            $Fake.WrittenSha = @($args) | Where-Object { $_ -like 'sha=*' }
            return '{}'
        }
        default { Add-Call "unexpected: $line"; $global:LASTEXITCODE = 1; return "unexpected gh $line" }
    }
}
function Invoke-WebRequest {
    param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
    Add-Call 'download'
    $Fake.DownloadedUri = $Uri
    $from = if ($Fake.DownloadFile) { $Fake.DownloadFile } else { $Fake.Uploaded }
    Copy-Item -LiteralPath $from -Destination $OutFile -Force
}

try {
    # --- 저장소 흉내: 2.0.1, 태그 v2.0.1, origin/main 에 병합됨 ---
    New-Item -ItemType Directory -Force -Path (Join-Path $repo 'scripts') | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update-repo.ps1'), (Join-Path $PSScriptRoot 'publish-android-update.ps1') -Destination (Join-Path $repo 'scripts')
    $gradleFile = Join-Path $repo 'app\build.gradle.kts'
    Write-Text $gradleFile "android {`n    defaultConfig {`n        versionCode = 20001`n        versionName = `"2.0.1`"`n    }`n}`n"
    Write-Text (Join-Path $repo '.gitignore') "build/`napp/build/`n"
    Write-Text (Join-Path $repo 'gradlew.bat') (@(
        '@echo off'
        'echo build>>"%KORAIL_TEST_LOG%"'
        'if not "%KORAIL_TEST_GRADLE_EXIT%"=="0" exit /b %KORAIL_TEST_GRADLE_EXIT%'
        'if not exist app\build\outputs\apk\release mkdir app\build\outputs\apk\release'
        'copy /y "%KORAIL_TEST_FAKE%\app-release.apk" app\build\outputs\apk\release\app-release.apk >nul'
        'copy /y "%KORAIL_TEST_FAKE%\output-metadata.json" app\build\outputs\apk\release\output-metadata.json >nul'
        'exit /b 0') -join "`r`n")
    Write-Text (Join-Path $fakeDir 'sdk\build-tools\35.0.0\apksigner.bat') (@(
        '@echo off'
        'echo apksigner>>"%KORAIL_TEST_LOG%"'
        'echo Verifies'
        'echo Signer #1 certificate DN: CN=test'
        'echo Signer #1 certificate SHA-256 digest: %KORAIL_TEST_SIGNER%'
        'exit /b 0') -join "`r`n")
    $apkBytes = New-Object byte[] 4096; (New-Object Random 7).NextBytes($apkBytes)
    [IO.File]::WriteAllBytes((Join-Path $fakeDir 'app-release.apk'), $apkBytes)
    $apkSha = (Get-FileHash -LiteralPath (Join-Path $fakeDir 'app-release.apk') -Algorithm SHA256).Hash
    Write-Text (Join-Path $fakeDir 'output-metadata.json') '{"applicationId":"com.korailmacro.app","variantName":"release","elements":[{"versionCode":20001,"versionName":"2.0.1","outputFile":"app-release.apk"}]}'
    Write-Text (Join-Path $fakeDir 'other.apk') 'tampered'

    & { $ErrorActionPreference = 'Continue'; & git init --bare --quiet (Join-Path $fakeDir 'origin.git') 2>&1 | Out-Null }
    Invoke-SandboxGit init --quiet -b main
    Invoke-SandboxGit config user.email test@example.com
    Invoke-SandboxGit config user.name test
    Invoke-SandboxGit config core.autocrlf false
    Invoke-SandboxGit add -A
    Invoke-SandboxGit commit --quiet -m test
    Invoke-SandboxGit tag v2.0.1
    Invoke-SandboxGit remote add origin (Join-Path $fakeDir 'origin.git')
    Invoke-SandboxGit push --quiet origin main

    $env:ANDROID_HOME = Join-Path $fakeDir 'sdk'; $env:ANDROID_SDK_ROOT = $null
    $env:KORAIL_TEST_LOG = $log; $env:KORAIL_TEST_FAKE = $fakeDir
    $published200 = '{"versionCode":20000,"versionName":"2.0.0","apkUrl":"https://x/a.apk","sha256":"' + ('A' * 64) + '","notes":""}'
    $failures = 0

    function Test-Case([string]$Name, [hashtable]$Setup, [string[]]$Expect, [string]$ErrorText = '', [switch]$DryRun) {
        $script:Fake = @{ Visibility = 'PUBLIC'; Published = $published200; ReleaseExists = $false; DownloadFile = $null }
        foreach ($k in $Setup.Keys) { $Fake[$k] = $Setup[$k] }
        $env:KORAIL_TEST_GRADLE_EXIT = if ($Setup.GradleExit) { "$($Setup.GradleExit)" } else { '0' }
        $env:KORAIL_TEST_SIGNER = if ($Setup.Signer) { $Setup.Signer } else { $signer }
        Set-Content -LiteralPath $log -Value $null
        $message = ''
        try { & (Join-Path $repo 'scripts\publish-android-update.ps1') -Notes '테스트 안내' -DryRun:$DryRun *> $null }
        catch { $message = $_.Exception.Message }
        $calls = @(Get-Content -LiteralPath $log | Where-Object { $_ })
        $problems = @()
        if (($calls -join ',') -ne ($Expect -join ',')) { $problems += "호출 순서 $($calls -join ' → ')" }
        if ($ErrorText -and $message -notlike "*$ErrorText*") { $problems += "오류 문구 '$message'" }
        if (-not $ErrorText -and $message) { $problems += "예상하지 못한 실패 '$message'" }
        if ($Setup.Check) { $problems += @(& $Setup.Check) }
        if ($problems) { $script:failures++; Write-Host "실패  $Name"; $problems | ForEach-Object { Write-Host "      $_" } }
        else { Write-Host "통과  $Name ($($calls -join ' → '))" }
    }

    $before = @('visibility', 'published-version', 'release-exists')
    $full = $before + @('build', 'apksigner', 'release-exists', 'upload', 'download', 'json-sha', 'json-update')
    $url = 'https://github.com/ppcciiss2-ux/korail-macro-updates/releases/download/android-2.0.1/KorailMacro-2.0.1.apk'

    Test-Case '정상 게시: 공개·역행 확인 → 빌드 → 서명 → 업로드 → 다시 받기 → JSON' @{ Check = {
            if ($Fake.Written.versionCode -ne 20001 -or $Fake.Written.versionName -ne '2.0.1') { "JSON 버전 $($Fake.Written.versionCode) $($Fake.Written.versionName)" }
            if ($Fake.Written.sha256 -ne $apkSha) { "JSON sha256 $($Fake.Written.sha256)" }
            if ($Fake.Written.apkUrl -ne $url -or $Fake.DownloadedUri -ne $url) { "주소 $($Fake.Written.apkUrl) / $($Fake.DownloadedUri)" }
            if ($Fake.Written.notes -ne '테스트 안내') { "notes $($Fake.Written.notes)" }
            if (-not $Fake.WrittenSha) { '기존 JSON 의 sha 없이 덮어씀' }
        } } $full
    Test-Case '첫 게시 (JSON 없음)' @{ Published = $null } $full
    Test-Case '연습 실행은 올리지 않음' @{} ($before + @('build', 'apksigner')) -DryRun
    Test-Case '비공개 저장소면 빌드 전에 멈춤' @{ Visibility = 'PRIVATE' } @('visibility') '공개가 아닙니다'
    Test-Case '같은 versionCode 가 게시돼 있으면 멈춤' @{ Published = $published200.Replace('20000', '20001') } @('visibility', 'published-version') '이미 versionCode 20001'
    Test-Case '더 높은 버전이 게시돼 있으면 멈춤' @{ Published = $published200.Replace('20000', '20100') } @('visibility', 'published-version') '이미 versionCode 20100'
    Test-Case '같은 릴리스 태그가 있으면 멈춤' @{ ReleaseExists = $true } $before '이미 게시했습니다'
    Test-Case '테스트·빌드 실패면 올리지 않음' @{ GradleExit = 1 } ($before + 'build') '실패해 게시하지 않습니다'
    Test-Case '서명 키가 다르면 올리지 않음' @{ Signer = 'ab' * 32 } ($before + @('build', 'apksigner')) '서명 키가 기존 설치본과 다릅니다'
    Test-Case '다시 받은 파일이 다르면 JSON 을 바꾸지 않음' @{ DownloadFile = (Join-Path $fakeDir 'other.apk') } ($before + @('build', 'apksigner', 'release-exists', 'upload', 'download')) 'SHA-256 이 다릅니다'

    Write-Text (Join-Path $repo 'stray.txt') 'x'
    Test-Case '커밋하지 않은 변경이 있으면 아무것도 하지 않음' @{} @() '커밋하지 않은 변경'
    Remove-Item -LiteralPath (Join-Path $repo 'stray.txt')
    Write-Text $gradleFile ((Get-Content -LiteralPath $gradleFile -Raw).Replace('20001', '20000'))
    Test-Case 'versionCode 와 versionName 이 맞지 않으면 아무것도 하지 않음' @{} @() '20001 로 바꾸세요'
    Invoke-SandboxGit checkout -- app/build.gradle.kts

    Write-Host ''
    if ($failures) { Write-Host "실패 $failures 건"; exit 1 }
    Write-Host '모두 통과'
}
finally {
    $env:ANDROID_HOME = $savedEnv.ANDROID_HOME; $env:ANDROID_SDK_ROOT = $savedEnv.ANDROID_SDK_ROOT
    Remove-Item Env:KORAIL_TEST_LOG, Env:KORAIL_TEST_FAKE, Env:KORAIL_TEST_GRADLE_EXIT, Env:KORAIL_TEST_SIGNER -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}
