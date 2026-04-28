# MIJI AI Service

Python service for the AI components of MIJI: YOLOv8 detection, CNN classification, and SymPy math solving. Exposes an HTTP API at `localhost:8000` that the Kotlin backend (`../server/`) calls.

## Folder structure

```
ai/
├── train/                # Offline model training
│   ├── train_cnn.py      # Train CNN on MNIST + BHMSDS
│   └── train_yolo.py     # Fine-tune YOLOv8 on math symbols
├── inference/            # Runtime AI service
│   ├── server.py         # FastAPI app — entry point
│   ├── pipeline.py       # YOLO -> CNN -> SymPy orchestration
│   ├── detect.py         # YOLOv8 inference
│   ├── classify.py       # CNN inference
│   └── solve.py          # SymPy step-by-step solving
├── data/                 # Datasets (gitignored)
├── models/               # Trained weights (gitignored)
├── requirements.txt
└── README.md
```

## Setup (planned)

```bash
# 1. Create a virtual environment
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate

# 2. Install dependencies
pip install -r requirements.txt

# 3. Run the inference service (once implemented)
uvicorn inference.server:app --host 127.0.0.1 --port 8000
```

## How it fits into MIJI

```
[Android]  --Wi-Fi sockets-->  [Kotlin server/]  --HTTP localhost:8000-->  [Python ai/]
                                                                             |
                                                          YOLOv8 + CNN + SymPy
```

The Kotlin backend handles all networking with the Android devices and session logic. This Python service only does the AI work.
