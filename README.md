# 🦸‍♂️ Super Daddy (슈퍼 대디)

**"아이를 처음 키우는 초보 아빠를 위한 AI 육아 도우미 서비스"**

Super Daddy는 초보 아빠들이 육아 중에 겪는 다양한 궁금증을 해결해 주고, 신뢰할 수 있는 육아 정보를 제공하는 대화형 AI 챗봇 서비스입니다. Google의 Gemini AI 모델과 RAG(Retrieval-Augmented Generation) 기술을 활용하여 `parenting_guide.pdf`와 같은 전문 자료를 기반으로 답변을 제공합니다.

## 🛠 기술 스펙 (Tech Stack)

### Backend
- **Language:** Java 25
- **Framework:** Spring Boot 3.5.10-SNAPSHOT
- **AI Integration:** Spring AI 1.1.2 (Google Gemini)
- **Build Tool:** Gradle 9.2.1

### Frontend
- **Template Engine:** Thymeleaf
- **Languages:** HTML5, CSS3, JavaScript (Vanilla ES6+)
- **Libraries:**
  - `marked.js`: AI 응답의 Markdown 렌더링 지원
  - `FontAwesome`: UI 아이콘
- **Styling:** Custom CSS (Flexbox layout, Mobile-first design)

### Infrastructure & Tools
- **Container:** Docker (Docker Compose 지원)
- **Vector Store:** Qdrant
- **VCS:** Git

---

## ✨ 주요 기능 (Key Features)

### 1. 💬 AI 육아 상담 채팅
- **실시간 대화:** 사용자의 질문에 대해 자연어 처리된 AI 답변을 실시간으로 제공합니다.
- **RAG 기반 답변:** 내부적으로 보유한 육아 가이드 문서(PDF)를 참조하여 환각(Hallucination)을 줄이고 정확한 정보를 제공합니다.
- **Markdown 렌더링:** AI가 제공하는 답변의 가독성을 높이기 위해 볼드체, 리스트, 코드 블록 등을 시각적으로 렌더링합니다.

### 2. 🎨 사용자 친화적 UI/UX
- **모바일 최적화 디자인:** 카카오톡과 유사한 친숙한 채팅 인터페이스를 제공합니다.
- **슈퍼 대디 프로필:** 친근감을 주는 커스텀 SVG 프로필 이미지를 적용했습니다.
- **반응형 메시지 버블:** 텍스트 길이에 따라 유동적으로 늘어나는 말풍선 디자인을 적용했습니다.

### 3. 🔍 편의 기능
- **대화 내용 검색:**
  - 상단 돋보기 아이콘을 통해 이전 대화 내용을 검색할 수 있습니다.
  - 검색어 하이라이팅(Highlight) 및 이전/다음 검색 결과 이동 기능을 지원합니다.
- **자동 날짜 구분선:**
  - 메시지를 주고받은 날짜가 변경되거나, 새로운 날짜에 접속 시 자동으로 날짜 구분선(예: `2025년 12월 26일 금요일`)이 생성됩니다.
- **심플 모드:**
  - 불필요한 기능(파일 첨부, 이모티콘 등)을 제거하고 대화에만 집중할 수 있는 깔끔한 입력창을 제공합니다.

---

## 📂 프로젝트 구조 (Project Structure)

```
super-daddy/
├── src/
│   ├── main/
│   │   ├── java/com/zoontopia/superdaddy/
│   │   │   ├── controller/      # ChatController (웹 요청 처리)
│   │   │   ├── service/         # ChatService, IngestionService (비즈니스 로직 & AI 연동)
│   │   │   └── SuperDaddyApplication.java
│   │   │
│   │   └── resources/
│   │       ├── static/
│   │       │   ├── css/         # style.css (전체 스타일링)
│   │       │   ├── js/          # script.js (프론트엔드 로직: 검색, 날짜, 채팅 처리)
│   │       │   └── images/      # superdaddy_profile.svg (프로필 이미지)
│   │       ├── templates/       # chat.html (메인 채팅 화면)
│   │       ├── parenting_guide.pdf # RAG 참조용 문서
│   │       └── application.yml  # 설정 파일 (Gemini API Key 등)
│   │
│   └── test/                    # JUnit 테스트 코드
├── build.gradle                 # 의존성 및 빌드 설정
├── docker-compose.yml           # Qdrant 실행 설정
└── README.md                    # 프로젝트 문서
```

## 🚀 실행 방법 (Getting Started)

### 사전 요구 사항
- Java 25 이상 설치
- Docker Desktop 설치 (Qdrant 실행용)
- Google Cloud Project ID 및 Gemini API Key 발급

### 1. Vector Store 실행
Qdrant 벡터 DB를 실행합니다.
```bash
docker-compose up -d
```

### 2. 설정 (Configuration)
`src/main/resources/application.yml` 파일 또는 환경 변수를 통해 API 키와 설정을 등록합니다.
*(보안을 위해 환경 변수 사용을 권장합니다)*

```yaml
spring:
  ai:
    google:
      genai:
        project-id: ${GOOGLE_PROJECT_ID}
        api-key: ${GOOGLE_API_KEY}
    vectorstore:
      qdrant:
        host: ${QDRANT_HOST} # 예: localhost
        port: ${QDRANT_PORT} # 예: 6334
```

**필수 환경 변수:**
- `GOOGLE_PROJECT_ID`: Google Cloud 프로젝트 ID
- `GOOGLE_API_KEY`: Google Gemini API Key
- `QDRANT_HOST`: Qdrant 호스트 (로컬 실행 시 `localhost`)
- `QDRANT_PORT`: Qdrant 포트 (기본값 `6334`)

### 3. 실행 (Run)
터미널에서 다음 명령어를 실행합니다.

**Windows:**
```bash
./gradlew bootRun
```

**Mac/Linux:**
```bash
./gradlew bootRun
```