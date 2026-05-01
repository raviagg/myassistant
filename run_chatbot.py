#!/usr/bin/env python3
"""
Launcher — no pip install required.

Usage:
    python run_chatbot.py --person-id <UUID>
    python run_chatbot.py --person-id <UUID> --verbose
    python run_chatbot.py --person-id <UUID> --backend claude-p
"""
import sys
from pathlib import Path

_root = Path(__file__).parent
sys.path.insert(0, str(_root / "client" / "common"))
sys.path.insert(0, str(_root / "client" / "chatbot"))

from chatbot.chatbot import main  # noqa: E402

main()
