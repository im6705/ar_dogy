# AR Dogy · Android

제공된 USDZ 강아지를 Android 카메라 화면의 바닥에 놓고 돌아다니게 하는 앱입니다. Android 공간 추적은 **Google ARCore**, 렌더링은 **SceneView / Filament**를 사용합니다. Apple ARKit은 사용하지 않습니다.

## 설치하고 사용하기

1. [GitHub Releases](https://github.com/im6705/ar_dogy/releases)에서 APK를 다운로드해 설치합니다.
2. ARCore 지원 Android 기기에서 실행하고 카메라 권한을 허용합니다. Google Play Services for AR 설치/업데이트 안내가 뜨면 완료합니다.
3. 밝고 무늬가 있는 바닥을 카메라로 천천히 비춥니다. 넓게 인식된 바닥 안쪽을 누르면 강아지가 나타납니다.
4. 강아지가 인식된 평면 안에서 걷고 쉬기를 반복합니다. 화면의 일시정지/계속 및 다시 놓기 버튼으로 조작합니다.

평면 가장자리에는 강아지의 크기를 반영한 여유 공간이 필요합니다. 추적이 끊기면 이동을 멈추며, 바닥이 좁아지면 다시 스캔하거나 배치해야 합니다. 이 앱은 실제 가구·벽의 충돌을 감지하는 내비게이션을 제공하지 않습니다.

## 모델과 재현

- 원본: `assets-source/dog-source.usdz` — 사용자가 제공한 원본을 보존합니다.
- 런타임 모델: `app/src/main/assets/models/dog.glb` — 텍스처와 스키닝, `Idle` / `Walk` 애니메이션을 포함합니다.
- `tools/assets/`의 변환 도구와 명시적 뼈대 매핑으로 개발 시 USDZ를 GLB로 변환합니다. 앱 안에서 USD 파일을 직접 선택하는 방식은 아닙니다.
- 모델 좌표는 미터, Y 위쪽, +Z 정면, 발바닥 Y=0을 기준으로 합니다. 다른 모델은 해당 뼈대에 맞는 매핑이 필요합니다.

원본은 걷기 애니메이션이 없는 앉은 자세의 54개 뼈대 모델입니다. 동작은 원본의 스킨과 실제 관절을 이용해 생성하며, 다른 모델의 애니메이션이나 라이선스를 가져오지 않습니다. 원본 모델에 대한 권리는 원래 권리자에게 있습니다.

현재 걷기는 초기 미리보기 동작입니다. 앉은 원본을 세운 영향으로 뒷다리 부근에 주름과 일부 메시 돌출이 보입니다. 매끄러운 최종 보행을 위해서는 서 있는 자세의 원본이나 스킨 가중치 보정이 필요합니다.

## 빌드와 릴리즈

JDK 21, Android SDK 36을 설치한 뒤:

```powershell
./gradlew.bat :app:assembleRelease
```

macOS/Linux에서는 `./gradlew :app:assembleRelease`를 사용합니다. APK 출력은 `app/build/outputs/apk/release/`입니다.

GitHub Actions가 깨끗한 체크아웃에서 APK를 빌드합니다. `v*` 태그를 푸시하면 APK, 체크섬과 빌드 메타데이터를 GitHub의 **사전 릴리즈**로 게시합니다. 빌드 실패 시 릴리즈를 게시하지 않습니다.

```sh
git tag v0.1.0
git push origin v0.1.0
```

배포 APK는 저장소에 명시된 **공개 개발용 키**로 서명합니다. 개인 기기 테스트용이며 Google Play 운영 배포 서명이 아닙니다. 운영 배포 시 별도의 비밀 서명키와 배포 설정이 필요합니다.

## 검증 범위

이동 영역의 단위 테스트는 `./gradlew :app:testReleaseUnitTest`로 실행합니다. 좁고 긴 바닥,
평면 방향, 안전 여백, 앵커 거리 제한 및 무작위 후보 선택 실패 시 대체 경로를 검사합니다.
`scripts/build-apk.sh`와 GitHub Actions도 이 테스트를 실행한 뒤 APK를 검증합니다.

실제 Android 기기는 이번 작업에 연결되지 않았습니다. APK 빌드·서명·포함 자산 검증과 실제 기기의 카메라/AR 동작 검증은 구분합니다. [기기 테스트 절차](docs/device-acceptance.md)에 따라 바닥 인식, 배치, 이동, 추적 중단, 재개를 확인할 수 있습니다.

기술 기준: [ARCore 기기 지원](https://developers.google.com/ar/devices), [Android AR 설정](https://developers.google.com/ar/develop/java/enable-arcore), [앵커 동작](https://developers.google.com/ar/develop/anchors), [Blender USD 지원](https://docs.blender.org/manual/en/4.2/files/import_export/usd.html).
