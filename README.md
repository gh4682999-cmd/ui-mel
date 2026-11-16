# 멜론 턴테이블 (안드로이드 앱)

## GitHub에서 APK 빌드 (설치 불필요)
1. 이 폴더 내용을 GitHub 저장소에 올리기 (루트에 `build.gradle.kts`, `.github` 폴더가 오도록)
2. 저장소의 **Actions** 탭 > **Build APK** > **Run workflow**
   (`main` 브랜치에 push해도 자동으로 빌드됩니다)
3. 끝나면 실행 화면 아래 **Artifacts**에서 `melon-turntable-debug-apk` 다운로드 → zip 안의 `melon-turntable.apk`
4. (선택) 태그를 푸시하면 **Releases**에 APK가 자동 첨부됩니다: `git tag v1.0 && git push origin v1.0`

> `debug.keystore`를 저장소에 포함해 두었기 때문에 매번 같은 서명으로 빌드되어,
> 새 APK를 기존 앱 위에 그대로 덮어 설치(업데이트)할 수 있습니다. (개인 설치용, 스토어 배포용 아님)

## 로컬 빌드 (Android Studio)
Gradle Sync → Build > Build APK(s) → `app/build/outputs/apk/debug/app-debug.apk`

## 폰에 설치
APK를 폰으로 옮겨 실행 → '출처를 알 수 없는 앱 설치' 허용 → 설치.
처음 실행 시 알림 접근 권한에서 '멜론 턴테이블'을 켜면 멜론과 연동됩니다.
