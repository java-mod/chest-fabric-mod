# changojigi-fabric-mod

마인크래프트 클라이언트에서 상자 사용 내역과 섬 은행 입출금 메시지를 감지해 `chest-discord-bot` 백엔드로 전송하는 Fabric 클라이언트 모드입니다.

## 요구 사항

- Minecraft `1.21.4`
- Fabric Loader `0.16.10+`
- Java `21`
- 동작 중인 `chest-discord-bot` 서버

## 주요 기능

- 참여 코드로 섬 설정 동기화
- 상자 열기/닫기 차이를 계산해 창고 로그 전송
- 관리자 코드 기반 상자 등록
- 섬 은행 입금/출금 메시지 감지
- 은행 거래 사유 입력 후 백엔드 전송

## 빌드

Windows:

```powershell
.\gradlew.bat build
```

macOS / Linux:

```bash
./gradlew build
```

빌드 결과물은 `build/libs/` 아래에 생성됩니다.

## 설치

1. Fabric 1.21.4 환경을 준비합니다.
2. 빌드한 모드 jar를 Minecraft의 `mods/` 폴더에 넣습니다.
3. 게임을 한 번 실행해 `config/chestbot.json`을 자동 생성합니다.
4. 필요하면 `config/chestbot.json`을 수정합니다.

## 설정 파일

경로: `<게임 디렉터리>/config/chestbot.json`

예시:

```json
{
  "server_url": "http://15.235.143.55:5000",
  "island_code": "ABC123"
}
```

설명:

- `server_url`: `chest-discord-bot` 서버 주소
- `island_code`: Discord 봇에서 발급받은 참여 코드. 처음에는 비워두고 인게임 명령어로 연결해도 됩니다.

기존 설정 파일이 있으면 코드 기본값보다 **설정 파일 값이 우선**합니다.

## 인게임 명령어

영문/한글 둘 다 지원합니다.

### 일반 사용자

- `/chestbot connect <코드>` / `/창고봇 연결 <코드>`
- `/chestbot list` / `/창고봇 목록`
- `/chestbot reload` / `/창고봇 새로고침`
- `/chestbot depositreason <사유>` / `/창고봇 입금사유 <사유>`

`depositreason` 명령은 현재 이름이 그대로 유지되지만, 실제로는 **입금/출금 둘 다** 처리합니다.

### 관리자

- `/chestbot admin <관리자코드>` / `/창고봇 관리자 <코드>`
- `/chestbot add <창고이름>` / `/창고봇 추가 <창고이름>`
- `/chestbot remove <창고이름>` / `/창고봇 제거 <창고이름>`

관리자 모드에서는 `add` 실행 후 실제 상자를 우클릭하면 등록됩니다.

## 사용 흐름

1. Discord 서버에서 `chest-discord-bot`을 실행하고 `/창고 설정`으로 섬을 생성합니다.
2. Discord 응답으로 받은 참여 코드를 인게임에서 `/창고봇 연결 <코드>`로 입력합니다.
3. 필요하면 Discord에서 `/창고 관리자코드`를 발급받아 `/창고봇 관리자 <코드>`로 관리자 모드에 들어갑니다.
4. 창고를 등록하고 일반 플레이 중 상자 사용 로그와 섬 은행 거래 로그를 자동 전송합니다.
5. 섬 은행 입금/출금 감지 시 채팅 프롬프트가 뜨면 `/창고봇 입금사유 <사유>`로 사유를 보냅니다.

## 섬 은행 로그 동작 방식

- `...님이 섬 은행에 ... 입금했어요`
- `...님이 섬 은행에서 ... 출금했어요`

형태의 메시지를 감지합니다.

메시지 앞에 장식 문자나 서버 포맷 문자가 붙어도, 플레이어 이름에서 실제 닉네임을 추출해 비교하도록 되어 있습니다.

거래가 감지되면 먼저 채팅에 사유 입력 안내가 표시되고, 사용자가 사유를 입력한 뒤에만 백엔드로 최종 로그가 전송됩니다.

## 백엔드 연동 경로

이 모드는 현재 아래 경로를 사용합니다.

- `POST /api/v1/client/connect`
- `POST /api/v1/client/admin/connect`
- `POST /api/v1/client/admin/finalize`
- `POST /api/v1/client/events/chest-log`
- `POST /api/v1/client/events/island-bank-log`

백엔드 프로젝트와 API 계약이 다르면 연결은 되어도 로그 전송이 실패할 수 있습니다.

## 문제 해결

### 서버 연결 실패

- `server_url`이 올바른지 확인합니다.
- 실제 백엔드가 해당 포트에서 외부 접속 가능한지 확인합니다.
- 기존 `config/chestbot.json`이 오래된 주소를 덮어쓰고 있지 않은지 확인합니다.

### 연결은 되는데 로그가 안 보임

- Discord 쪽에서 로그 채널 연결이 되었는지 확인합니다.
- 상자 등록이 완료됐는지 확인합니다.
- 섬 은행 거래는 감지 후 사유 입력까지 해야 최종 전송됩니다.

### 설정 초기화

`config/chestbot.json`을 삭제하고 게임을 다시 실행하면 기본 설정 파일이 다시 생성됩니다.

## 개발 메모

- 모드 ID: `chestbot`
- 표시 이름: `창고지기`
- 환경: `client`
- 기본 서버 URL: `http://15.235.143.55:5000`
