# backend/server — Kotlin / JVM

Kotlin module for MIJI's backend. Runs on the teacher's laptop. Handles all networking with the Android devices over local Wi-Fi and delegates AI work to the Python service in `../ai/`.

## Folder structure

```
server/
├── build.gradle.kts
└── src/main/kotlin/com/miji/server/
    ├── Main.kt              # TCP socket server entry point
    ├── SessionManager.kt    # Teacher <-> student session sync
    ├── AiClient.kt          # HTTP client for the Python AI service
    └── Protocol.kt          # Message format shared with the Android app
```

## How it fits into MIJI

```
[Android]  --Wi-Fi sockets-->  [server/ (this module)]  --HTTP-->  [ai/ (Python)]
```

- **Inbound:** raw TCP from Android devices on the school's local Wi-Fi
- **Outbound:** HTTP requests to the Python AI service at `http://localhost:8000`

## Run (once implemented)

From the project root:

```bash
./gradlew :backend:server:run
```
