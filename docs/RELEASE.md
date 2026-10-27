# 업데이트 게시

2.0.0부터 앱 안에서 새 버전을 받아 설치한다. 배포 방식은 PC 리모트(`pc-remote-updates`)와 같고,
앱이 스스로 확인하지 않고 **사용자가 [설정] → [업데이트]를 누를 때만** 확인한다는 점만 다르다
(2026-10-26 사용자 결정).

## 앱 동작

- 앱을 켤 때, 화면으로 돌아올 때, 백그라운드에서는 확인하지 않고 알림도 띄우지 않는다.
  확인 요청은 업데이트 화면(`update/UpdateActivity.kt`)에서만 나간다.
- 업데이트 화면에 들어가면 바로 한 번 확인하고, 오른쪽 위 [다시 확인]으로 다시 확인한다.
- 한 줄로 보여 준다: `앱(2.0.0)` 아래에
  - 확인 중: "업데이트를 확인하는 중…"
  - 최신: ✓ "앱이 최신입니다."
  - 새 버전: "새 버전을 사용할 수 있습니다 (2.0.1)", 안내 문구, [업데이트]
  - 오류: "업데이트를 확인하지 못했습니다." 와 이유 (인터넷 연결, HTTP 404·5xx, 형식 오류)
- [업데이트] → https로 받기 → SHA-256 확인 → 패키지 이름·versionCode 확인 → 설치 권한이 없으면
  "출처를 알 수 없는 앱 설치" 설정으로 안내(허용하고 돌아오면 이어서 진행) → Android 설치 화면
  - 받는 파일은 앱 내부 캐시(`cacheDir/updates`)에 두고 FileProvider로 설치 화면에 넘긴다
  - https → http 리다이렉트는 따르지 않는다. 100MB를 넘으면 받지 않는다
  - 하나라도 맞지 않으면 파일을 지우고 이유를 보인다. 서명은 Android 설치 화면이 확인한다
- 자동 예약이 실행 중이면 "설치하면 자동 예약이 멈춥니다"를 함께 보인다

## 게시 위치

| 저장소 | 공개 | 내용 |
|---|---|---|
| `ppcciiss2-ux/korail-macro_sh` | 공개 | 소스·문서·게시 스크립트 |
| [`ppcciiss2-ux/korail-macro-updates`](https://github.com/ppcciiss2-ux/korail-macro-updates) | **공개** | main: `android-update.json`·README. Releases: APK |

- 정보 주소: `AppUpdater.UPDATE_INFO_URL` = `https://raw.githubusercontent.com/ppcciiss2-ux/korail-macro-updates/main/android-update.json`
- 파일 주소: `https://github.com/ppcciiss2-ux/korail-macro-updates/releases/download/android-<버전>/KorailMacro-<버전>.apk`
- APK를 git 기록이 아닌 Releases에 두어 저장소가 커지지 않는다
- 업데이트 저장소에는 게시 스크립트로만 쓴다. 쓰는 일은 **사용자가 "배포 시작해"라고 할 때만** 한다

## `android-update.json`

```json
{
  "versionCode": 20001,
  "versionName": "2.0.1",
  "apkUrl": "https://github.com/ppcciiss2-ux/korail-macro-updates/releases/download/android-2.0.1/KorailMacro-2.0.1.apk",
  "sha256": "64자리 16진수 (대소문자 무관)",
  "notes": "업데이트 화면에 보일 한 줄 설명"
}
```

- `versionCode`가 설치된 앱보다 클 때만 새 버전으로 보인다. 같거나 낮으면 "앱이 최신입니다"(내려가지 않음)
- `apkUrl`은 https만. `sha256`이 64자리 16진수가 아니면 형식 오류로 본다. `notes`는 없어도 된다

## 버전과 태그

- `app/build.gradle.kts`의 `versionName`은 `x.y.z`, `versionCode`는 `x*10000 + y*100 + z` (2.0.0 → 20000).
  게시 스크립트가 둘이 맞지 않으면 멈춘다. 지금까지 배포한 값(1.4 = 5, 1.5 preview = 6~8)보다 크다
- 소스 저장소 태그: `vX.Y.Z` (main에 병합된 커밋). 업데이트 저장소 릴리스 태그: `android-X.Y.Z`

## 서명

- 모든 빌드(debug·preview·release)는 저장소에 커밋된 `signing/korail-macro.jks`로 서명한다.
  인증서 SHA-256: `686360867c47cefa18a963025621c9abc97933aedbdd11cafdfb11553f7cac65`.
  1.4 이후 배포한 APK가 모두 이 키라서 같은 키여야 기존 앱 위에 업데이트로 설치되고 로그인·텔레그램 정보가 남는다.
  게시 스크립트가 빌드한 APK의 인증서를 이 값과 비교한다
- 이 키와 비밀번호는 공개 저장소에 있어 누구나 같은 키로 서명할 수 있다. 앱 안 업데이트는
  `android-update.json`의 SHA-256과 맞는 파일만 설치하므로 이 경로로는 다른 파일이 설치되지 않는다.
  다른 곳에서 받은 APK는 설치하지 않는다. 키를 바꾸면 기존 사용자는 앱을 지우고 새로 설치해야 해서
  2.0.0에서는 그대로 둔다 (2026-10-26 사용자 결정)

## 게시 절차

필요한 것: GitHub CLI(`gh`)에 `ppcciiss2-ux` 계정으로 로그인(`gh auth status`), Android SDK(`ANDROID_HOME`),
JDK 17(Android Studio의 `jbr`).

1. `versionName`·`versionCode`를 올리고 PR → 교차 테스트 통과 → main 병합 → 병합 커밋에 태그 `vX.Y.Z`
2. 태그 커밋 그대로에서 실행한다. 작업 폴더가 그 커밋이 아니거나 커밋하지 않은 변경이 있으면
   `git worktree add --detach "$env:TEMP\korail-macro-vX.Y.Z" vX.Y.Z`로 받아 그 폴더에서 실행하고, 끝나면 `git worktree remove`
3. 연습: `./scripts/publish-android-update.ps1 -Notes "바뀐 점 한 줄" -DryRun` — 아래 1~5단계만 하고 올리지 않는다
4. 게시: `./scripts/publish-android-update.ps1 -Notes "바뀐 점 한 줄"`
   1. 커밋하지 않은 변경이 없고, 지금 커밋에 태그 `vX.Y.Z`가 있고, 그 커밋이 origin/main에 병합되어 있는지
   2. 업데이트 저장소가 공개인지
   3. 이미 게시한 versionCode보다 높은지, 같은 릴리스 태그가 없는지
   4. 단위 테스트와 release APK 빌드 (`:app:testReleaseUnitTest :app:assembleRelease`)
   5. APK의 패키지 이름·버전, 서명 인증서 SHA-256 → `build/updates/KorailMacro-X.Y.Z.apk`
   6. 릴리스 `android-X.Y.Z`에 APK 업로드
   7. 로그인 없이 다시 받아 SHA-256 확인
   8. `android-update.json` 갱신 — **이 순간 게시된다**
5. 확인: `https://raw.githubusercontent.com/ppcciiss2-ux/korail-macro-updates/main/android-update.json`이
   새 버전인지(GitHub 캐시로 최대 5분 늦을 수 있다), 릴리스 파일을 로그인 없이 받을 수 있는지, 휴대폰에서
   [설정] → [업데이트]가 새 버전을 보이는지

**중간에 실패하면**: JSON은 맨 마지막에 바꾸므로 앱에는 아무것도 보이지 않는다. 릴리스만 만들어졌다면
`gh release delete android-X.Y.Z --repo ppcciiss2-ux/korail-macro-updates --cleanup-tag` 뒤 다시 실행한다.

**게시한 버전에 문제가 있으면**: `android-update.json`만 직전 내용으로 되돌려 다른 기기가 받지 않게 하고 보고한다.
직전 내용은 업데이트 저장소의 커밋 기록에 있다. 이미 설치한 기기는 되돌아가지 않으므로 고친 버전을 새로 게시한다.

**처음 설치**: 1.4 이하에는 업데이트 화면이 없다. 2.0.0은 한 번 휴대폰 브라우저로
[Releases](https://github.com/ppcciiss2-ux/korail-macro-updates/releases)에서 받아 설치한다(같은 키라 1.4 위에 덮어써지고 정보가 남는다).
그다음부터는 앱의 업데이트 화면에서 받는다.

## 확인 방법

- 단위 테스트: `./gradlew.bat testDebugUnitTest` — `UpdateCheckTest`(JSON 해석·버전 비교·SHA-256·크기 상한)
- UI 테스트: `./gradlew.bat connectedPreviewAndroidTest` (전용 에뮬레이터에서만) — `UpdateScreenTest`가
  앱 실행·탭 이동·복귀·화면 재생성에서 확인하지 않는지, 업데이트 화면의 최신 / 새 버전 / 오류 상태를 네트워크 없이 확인한다
- 게시 스크립트: `./scripts/test-publish-android-update.ps1` — gh·내려받기·Gradle·apksigner를 가짜로 바꾼 임시 저장소에서
  정상 게시 순서와 멈춰야 하는 경우(비공개 저장소, 버전 역행, 같은 태그, 빌드 실패, 다른 서명, 다시 받은 파일 불일치,
  커밋하지 않은 변경, versionCode 불일치) 12가지를 확인한다. GitHub에는 아무것도 쓰지 않는다
