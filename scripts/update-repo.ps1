# 업데이트 전용 공개 저장소(korail-macro-updates) 게시 도우미. publish-android-update.ps1 이 불러 쓴다.
# PC 리모트(pc-remote-updates)와 같은 구조: APK 는 이 저장소의 Releases 에, 버전 정보 JSON 은 main 브랜치에 둔다.
# 앱은 로그인 없이 JSON 을 읽으므로 이 저장소는 공개여야 한다. 형식과 절차는 docs/RELEASE.md
$UpdateRepo = 'ppcciiss2-ux/korail-macro-updates'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI(gh)가 없습니다. winget install GitHub.cli 로 설치하고 gh auth login 으로 로그인하세요.'
}

function Invoke-Gh {
    # Windows PowerShell 5.1 은 오류 출력(stderr)을 받으면 ErrorActionPreference=Stop 에서 바로 멈추므로 여기서만 Continue
    $ErrorActionPreference = 'Continue'
    $output = & gh @args 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{ Ok = ($LASTEXITCODE -eq 0); Output = ($output -join "`n") }
}

function Assert-UpdateRepo {
    $r = Invoke-Gh repo view $UpdateRepo --json visibility --jq .visibility
    if (-not $r.Ok) { throw "업데이트 저장소 $UpdateRepo 에 닿지 않습니다. gh auth status 로 로그인을 확인하세요.`n$($r.Output)" }
    if ($r.Output.Trim() -ne 'PUBLIC') { throw "업데이트 저장소 $UpdateRepo 가 공개가 아닙니다. 앱은 로그인 없이 읽으므로 공개여야 합니다." }
}

# 지금 게시된 버전 정보. 아직 없으면 $null
function Get-UpdateInfo([string]$Name) {
    $r = Invoke-Gh api "repos/$UpdateRepo/contents/$Name" --jq .content
    if (-not $r.Ok) {
        if ($r.Output -match '404|Not Found') { return $null }
        throw "게시된 $Name 을 읽지 못했습니다.`n$($r.Output)"
    }
    $bytes = [Convert]::FromBase64String(($r.Output -replace '\s', ''))
    [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
}

# 같은 태그의 릴리스가 이미 있으면 멈춘다 (빌드 전에 미리 확인)
function Assert-NewRelease([string]$Tag) {
    if ((Invoke-Gh release view $Tag --repo $UpdateRepo --json tagName).Ok) {
        throw "$Tag 는 이미 게시했습니다. 버전을 올려 다시 만드세요. (게시가 중간에 실패했다면 gh release delete $Tag --repo $UpdateRepo --cleanup-tag 뒤 다시 실행)"
    }
}

# 파일을 릴리스로 올리고 로그인 없이 받을 수 있는 https 주소를 돌려준다
function Publish-ReleaseAsset([string]$Tag, [string]$Title, [string]$Notes, [string]$File) {
    Assert-NewRelease $Tag
    $notesFile = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($notesFile, $Notes, (New-Object Text.UTF8Encoding($false)))
        $r = Invoke-Gh release create $Tag $File --repo $UpdateRepo --title $Title --notes-file $notesFile
        if (-not $r.Ok) { throw "릴리스 $Tag 를 만들지 못했습니다.`n$($r.Output)" }
    }
    finally { Remove-Item -LiteralPath $notesFile -Force -ErrorAction SilentlyContinue }
    "https://github.com/$UpdateRepo/releases/download/$Tag/$([IO.Path]::GetFileName($File))"
}

# 앱과 같은 방법(로그인 없이 https)으로 다시 받아 SHA-256 을 확인한다. 다르면 버전 정보를 바꾸지 않는다
function Confirm-PublishedFile([string]$Url, [string]$Sha256) {
    $temp = [IO.Path]::GetTempFileName()
    try {
        $ProgressPreference = 'SilentlyContinue'
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $Url -OutFile $temp -UseBasicParsing
        $actual = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash
        if ($actual -ne $Sha256) { throw "올린 파일을 다시 받아 보니 SHA-256 이 다릅니다 ($actual). 버전 정보를 바꾸지 않았습니다." }
    }
    finally { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
}

# 버전 정보 JSON 을 main 브랜치에 쓴다. 이 순간이 게시다 (앱의 업데이트 화면이 이 파일을 읽는다)
function Set-UpdateInfo([string]$Name, $Info, [string]$Message) {
    # BOM 없는 UTF-8 (Windows PowerShell 5.1 의 -Encoding utf8 은 BOM 을 붙인다)
    $json = $Info | ConvertTo-Json
    $content = [Convert]::ToBase64String((New-Object Text.UTF8Encoding($false)).GetBytes($json))
    $fields = @('-X', 'PUT', "repos/$UpdateRepo/contents/$Name", '-f', "message=$Message", '-f', "content=$content")
    $sha = Invoke-Gh api "repos/$UpdateRepo/contents/$Name" --jq .sha
    if ($sha.Ok) { $fields += @('-f', "sha=$($sha.Output.Trim())") }
    $r = Invoke-Gh api @fields
    if (-not $r.Ok) { throw "$Name 을 바꾸지 못했습니다. 파일은 릴리스에 올라갔지만 앱에는 아직 보이지 않습니다.`n$($r.Output)" }
    $json
}
