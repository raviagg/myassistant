#!/usr/bin/env python3
"""
Launcher — no pip install required.

Usage (from anywhere):
    python client/chatbot/run.py --person-id <UUID>
    python client/chatbot/run.py --person-id <UUID> --verbose
    python client/chatbot/run.py --person-id <UUID> --backend claude-p
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))  # adds client/ so `common` and `chatbot` are importable

from chatbot.chatbot import main  # noqa: E402

main()
