param(
    # 업데이트 화면에 보일 한 줄 설명
    [string]$Notes = '',
    # 확인·테스트·빌드·APK 검사까지만 하고 올리지 않는다. 태그·작업 폴더 검사는 경고로만 보인다
    [switch]$DryRun
)

# Android 앱 업데이트 게시: 업데이트 전용 공개 저장소(korail-macro-updates)의 Releases 에 APK 를 올리고 android-update.json 을 바꾼다.
# JSON 을 바꾸는 순간이 배포다 (앱의 [설정] → [업데이트]에서 새 버전이 보인다). 사용자가 "배포 시작해"라고 할 때만 실행한다.
# 순서: 태그·작업 폴더 → 저장소 공개 여부 → 버전 역행 → 단위 테스트·빌드 → APK 버전·서명 → 업로드 → 로그인 없이 다시 받아 SHA-256 → JSON.
# 버전은 app/build.gradle.kts 의 versionName(x.y.z)·versionCode(x*10000+y*100+z). 절차는 docs/RELEASE.md
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'update-repo.ps1')

# 1.4 부터 배포한 모든 APK 의 서명 인증서 SHA-256. 다르면 기존 앱 위에 업데이트로 설치되지 않는다
$ExpectedSigner = '686360867c47cefa18a963025621c9abc97933aedbdd11cafdfb11553f7cac65'
$ApplicationId = 'com.korailmacro.app'

function Write-Step([string]$Text) { Write-Host ''; Write-Host "== $Text" }

function Invoke-Git {
    $ErrorActionPreference = 'Continue'
    $output = & git -C $root @args 2>$null
    [pscustomobject]@{ Ok = ($LASTEXITCODE -eq 0); Output = (@($output) -join "`n").Trim() }
}

function Get-SdkTool([string]$Name) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path $root 'local.properties'
    if (-not $sdk -and (Test-Path -LiteralPath $localProps)) {
        $line = Get-Content -LiteralPath $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
    }
    if (-not $sdk) { throw 'Android SDK 위치를 모릅니다. ANDROID_HOME 을 설정하세요.' }
    $tool = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName $Name } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $tool) { throw "Android SDK build-tools 에서 $Name 을 찾지 못했습니다 ($sdk)." }
    $tool
}

# --- 버전 ---
$gradle = Get-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Raw -Encoding UTF8
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw 'build.gradle.kts 에서 versionCode 를 찾지 못했습니다.' }
$versionCode = [long]$Matches[1]
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'build.gradle.kts 에서 versionName 을 찾지 못했습니다.' }
$versionName = $Matches[1]
if ($versionName -notmatch '^(\d+)\.(\d+)\.(\d+)$') { throw "versionName 은 x.y.z 형식이어야 합니다 ($versionName)." }
$expectedCode = [long]$Matches[1] * 10000 + [long]$Matches[2] * 100 + [long]$Matches[3]
if ($versionCode -ne $expectedCode) { throw "versionCode($versionCode)가 versionName $versionName 과 맞지 않습니다. $expectedCode 로 바꾸세요." }
$tag = "android-$versionName"
if (-not $Notes) { $Notes = "코레일 매크로 $versionName" }
Write-Host "게시할 버전: $versionName (versionCode $versionCode) → $UpdateRepo 릴리스 $tag"

Write-Step '1/8 소스 태그와 작업 폴더'
$problems = @()
if ((Invoke-Git status --porcelain).Output) { $problems += '커밋하지 않은 변경이 있습니다. 태그 커밋 그대로에서 게시합니다.' }
$sourceTag = "v$versionName"
if (@((Invoke-Git tag --points-at HEAD).Output -split "`n") -notcontains $sourceTag) { $problems += "지금 커밋에 태그 $sourceTag 가 없습니다." }
elseif (-not (Invoke-Git fetch origin main --quiet).Ok -or -not (Invoke-Git merge-base --is-ancestor HEAD origin/main).Ok) {
    $problems += "태그 $sourceTag 커밋이 origin/main 에 병합되어 있지 않습니다."
}
if ($problems) {
    if (-not $DryRun) { throw ($problems -join "`n") }
    $problems | ForEach-Object { Write-Warning $_ }
}

Write-Step '2/8 업데이트 저장소가 공개인지'
Assert-UpdateRepo

Write-Step '3/8 게시된 버전보다 높은지'
$current = Get-UpdateInfo 'android-update.json'
if ($current -and ([long]$current.versionCode -ge $versionCode)) {
    throw "이미 versionCode $($current.versionCode) ($($current.versionName)) 를 게시했습니다. 버전을 올리세요."
}
Assert-NewRelease $tag
Write-Host ("지금 게시된 버전: " + $(if ($current) { "$($current.versionName) (versionCode $($current.versionCode))" } else { '없음' }))

Write-Step '4/8 단위 테스트와 release APK 빌드'
# 이 PC 의 JDK 는 사용자 임시 폴더에 소켓 파일을 만들지 못해 Gradle 이 멈춘다 → 위치만 바꾼다 (이 프로세스 안에서만)
if (-not $env:JAVA_TOOL_OPTIONS) { $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$env:SystemRoot\Temp" }
Push-Location $root
try {
    # Gradle 은 안내도 stderr 로 쓰므로 Stop 으로 두면 성공해도 멈춘다 → 종료 코드로만 판단
    & { $ErrorActionPreference = 'Continue'; & .\gradlew.bat :app:testReleaseUnitTest :app:assembleRelease --console=plain 2>&1 | ForEach-Object { "$_" } }
    if ($LASTEXITCODE -ne 0) { throw '단위 테스트나 APK 빌드가 실패해 게시하지 않습니다.' }
}
finally { Pop-Location }

Write-Step '5/8 APK 버전과 서명'
$outDir = Join-Path $root 'app\build\outputs\apk\release'
$meta = Get-Content -LiteralPath (Join-Path $outDir 'output-metadata.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$element = @($meta.elements)[0]
if ($meta.applicationId -ne $ApplicationId -or [long]$element.versionCode -ne $versionCode -or $element.versionName -ne $versionName) {
    throw "빌드한 APK 가 $ApplicationId $versionName ($versionCode) 가 아닙니다: $($meta.applicationId) $($element.versionName) ($($element.versionCode))"
}
$built = Join-Path $outDir $element.outputFile
$signer = & { $ErrorActionPreference = 'Continue'; & (Get-SdkTool 'apksigner.bat') verify --print-certs $built 2>&1 | ForEach-Object { "$_" } }
if ($LASTEXITCODE -ne 0) { throw "APK 서명 검사가 실패했습니다.`n$($signer -join "`n")" }
$digest = @($signer | ForEach-Object { if ($_ -match 'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})') { $Matches[1].ToLower() } })
if ($digest.Count -ne 1 -or $digest[0] -ne $ExpectedSigner) {
    throw "APK 서명 키가 기존 설치본과 다릅니다 ($($digest -join ', ')). 이대로 올리면 기존 앱 위에 설치되지 않습니다."
}
$apk = Join-Path $root "build\updates\KorailMacro-$versionName.apk"
New-Item -ItemType Directory -Path (Split-Path -Parent $apk) -Force | Out-Null
Copy-Item -LiteralPath $built -Destination $apk -Force
$sha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
$url = "https://github.com/$UpdateRepo/releases/download/$tag/$([IO.Path]::GetFileName($apk))"
$info = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl      = $url
    sha256      = $sha
    notes       = $Notes
}
Write-Host "APK: $apk ($([math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 1))MB, SHA-256 $sha)"

if ($DryRun) {
    Write-Host ''
    Write-Host '연습 실행이라 여기서 멈춥니다. 실제 게시라면 다음을 합니다:'
    Write-Host "  6/8 릴리스 $tag 에 $([IO.Path]::GetFileName($apk)) 업로드"
    Write-Host '  7/8 로그인 없이 다시 받아 SHA-256 확인'
    Write-Host '  8/8 android-update.json 을 아래 내용으로 갱신'
    $info | ConvertTo-Json | Write-Host
    return
}

Write-Step "6/8 릴리스 $tag 업로드"
$uploaded = Publish-ReleaseAsset -Tag $tag -Title "코레일 매크로 Android $versionName" -Notes $Notes -File $apk
if ($uploaded -ne $url) { throw "올린 파일 주소가 예상과 다릅니다: $uploaded" }

Write-Step '7/8 로그인 없이 다시 받아 SHA-256 확인'
Confirm-PublishedFile -Url $url -Sha256 $sha

Write-Step '8/8 android-update.json 갱신 (이 순간 게시됩니다)'
Set-UpdateInfo -Name 'android-update.json' -Message "Android $versionName" -Info $info | Write-Host
Write-Host ''
Write-Host "게시했습니다: $url"
Write-Host '앱의 [설정] → [업데이트]에서 확인합니다 (GitHub 캐시로 최대 5분 늦을 수 있음). 처음 설치는 휴대폰 브라우저로 위 주소를 열어 받습니다.'
