# MIJI Backend

Server-side code for MIJI. Runs on the teacher's laptop and serves the Android app over local Wi-Fi.

## Folder structure

```
backend/
├── server/    # Kotlin / JVM — Wi-Fi socket server, session logic
└── ai/        # Python      — YOLOv8, CNN, SymPy (FastAPI on localhost:8000)
```

## How the two pieces talk

```
[Android]  --Wi-Fi sockets-->  [backend/server/]  --HTTP localhost:8000-->  [backend/ai/]
                                                                              |
                                                          YOLOv8 + CNN + SymPy
```

- **server/** — the only thing the Android app sees. Handles all device networking and teacher↔student session sync.
- **ai/** — pure AI service. Only `server/` calls it, never the Android app directly.

## Running everything (once implemented)

In two terminals:

```bash
# Terminal 1 — start the Python AI service
cd backend/ai
source .venv/bin/activate          # Windows: .venv\Scripts\activate
uvicorn inference.server:app --host 127.0.0.1 --port 8000

# Terminal 2 — start the Kotlin backend (from project root)
./gradlew :backend:server:run
```

See `backend/server/README.md` and `backend/ai/README.md` for component details.
