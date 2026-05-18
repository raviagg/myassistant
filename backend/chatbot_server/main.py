import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from routers import auth, chat, embeddings, files

app = FastAPI(title="Personal Assistant Chatbot Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[os.environ.get("CORS_ORIGIN", "http://localhost:5173")],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(chat.router)
app.include_router(files.router)
app.include_router(embeddings.router)

@app.get("/health")
def health():
    return {"status": "ok"}
