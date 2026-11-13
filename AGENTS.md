# KorailMacro

코레일 예매를 도와주는 개인용 Android 앱입니다. 소스와 업데이트 저장소 모두 공개입니다.

공통 규칙은 전역 지침을 따릅니다. 이 파일에는 이 프로젝트 고유의 내용만 적습니다.

## 먼저 읽을 것
- 이 파일의 "현재 상태"
- 그 외 문서: `README.md`, `TESTING.md`, `docs/RELEASE.md`

## 명령
- 테스트와 검사: `./gradlew.bat assemblePreview testDebugUnitTest lintPreview`
- 기기 테스트: `./gradlew.bat connectedPreviewAndroidTest` (전용 테스트 에뮬레이터에서만)
- 빌드: `./gradlew.bat assembleDebug`, `./gradlew.bat assembleRelease`
- 게시: `./scripts/publish-android-update.ps1 -Notes "..."` (사용자가 "배포 시작해"라고 할 때만. 먼저 `-DryRun`)

## 새 작업 폴더 준비
- Git에서 제외되어 복사해야 할 파일: 없음 (`ANDROID_HOME`, `JAVA_HOME` 사용)
- 처음 한 번 실행할 명령: 없음

## 완료 조건
- `assemblePreview`, `testDebugUnitTest`, `lintPreview` 통과
- 실제 코레일 예매는 자동 테스트로 확인할 수 없어 사용자가 확인

## 구조 규칙
- `versionName`은 `x.y.z`, `versionCode`는 `x*10000 + y*100 + z`로 맞춥니다.
- 코레일 로그인 정보와 텔레그램 토큰은 코드, 문서, 로그에 남기지 않습니다.

## 사용자 결정 사항 (바꾸기 전에 반드시 확인)
- 서명키 `signing/korail-macro.jks`와 서명 비밀번호를 공개 저장소에 그대로 둡니다. 키를 바꾸면 기존 사용자가 앱을 지우고 다시 설치해야 하기 때문입니다. 다른 앱에 이 키를 쓰지 않습니다. (2026-10-26, 2026-11-05 재확인)

- 인증서 회귀 테스트는 인증서 파일 없이 구성하고, 2.0.2는 게시 후 휴대폰 확인을 진행합니다. (2026-11-14 사용자 승인)

## 현재 상태
- 목표: 2.0.2 인증서 교체 대응 배포 완료, 사용자 휴대폰 확인 대기
- 담당 도구, 브랜치, 커밋: Codex, `main`; 앱 태그 `v2.0.2`, 소스 `7c633d8138a4b41ae77ecddc05a3a9f08f924d32` (#10)
- 확인한 것: 자동 검사·preview/release 빌드, 실제 TLS 및 익명 조회, 별도 보안 검토, 기존 APK 서명, 공식 스크립트 게시, 공개 JSON 2.0.2/20002 및 APK SHA-256
- 확인하지 못한 것: 최종 2.0.2의 사용자 휴대폰 로그인·조회·예약 (게시 후 확인을 사용자 승인)
- 다음 할 일: 사용자가 설정→업데이트에서 설치 후 결과를 알려주면 해당 버전·커밋과 함께 기록
