# 파이어베이스 앱 배포 (Firebase App Distribution) 지침

디데이 넘김 프로젝트의 스마트폰 무선 배포는 **Firebase App Distribution**을 표준으로 사용합니다.

## 배포 정보
- **프로젝트 ID**: `couple-time-6b2ea`
- **앱 ID**: `1:946755314406:android:db376d34b6580073f19127`
- **패키지명**: `com.ddayflip.app`
- **테스터 계정**: `ljunnyl@gmail.com`, `ljunnyl@googlemail.com`, `skrua2157@gmail.com`
- **App Tester URL**: https://appdistribution.firebase.google.com/testerapps/1:946755314406:android:db376d34b6580073f19127

## 원클릭 배포 명령어
```powershell
$env:JAVA_HOME = "C:\Users\ljunn\.jdks\jbr-21.0.11"
.\android\gradlew -p android assembleDebug
npx firebase-tools appdistribution:distribute "android/app/build/outputs/apk/debug/app-debug.apk" --app "1:946755314406:android:db376d34b6580073f19127" --groups "testers" --testers "ljunnyl@gmail.com,ljunnyl@googlemail.com,skrua2157@gmail.com" --release-notes "릴리즈 노트"
```

자세한 운영 표준 및 트러블슈팅: [C:\Users\ljunn\secondbrain\AI\2026-10-파이어베이스-앱배포-스마트폰-무선테스트-표준.md](file:///C:/Users/ljunn/secondbrain/AI/2026-10-파이어베이스-앱배포-스마트폰-무선테스트-표준.md)
