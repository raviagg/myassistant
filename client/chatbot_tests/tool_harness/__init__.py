import sys
from pathlib import Path

# Add client/ to sys.path so `import common` works without pip install.
# This file is at client/chatbot_tests/tool_harness/__init__.py → parents[2] = client/
_client_dir = str(Path(__file__).parents[2])
if _client_dir not in sys.path:
    sys.path.insert(0, _client_dir)
