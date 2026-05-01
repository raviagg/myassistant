"""
Interactive CLI chatbot — personal assistant backed by the myassistant http_server.

Usage:
    python -m chatbot --person-id <UUID>
    python -m chatbot --person-id <UUID> --verbose
    python -m chatbot --person-id <UUID> --backend claude-p

Environment variables:
    CHATBOT_PERSON_ID   — person UUID of the logged-in user (alternative to --person-id)
    CHATBOT_HTTP_URL    — point at a running http_server; omit to auto-start
    CHATBOT_AUTH_TOKEN  — bearer token for http_server (default: dev token)
    CHATBOT_DB          — DB name for auto-start (default: myassistanttest)
    BEDROCK_API_KEY     — long-lived Bedrock API key
    AWS_*               — fallback SigV4 credentials for Bedrock
"""
import argparse
import os
import sys

from common.system_prompt import build_system_prompt, CHATBOT_PROMPT_ADDENDUM
from common.agentic_runner import AgenticRunner
from common.live_executor import LiveExecutor
from common.server_manager import managed_server, auth_token

# ── ANSI colors ───────────────────────────────────────────────────────────────

_RESET      = "\033[0m"
_BOLD       = "\033[1m"
_BLUE       = "\033[94m"    # user message label
_GREEN      = "\033[92m"    # assistant message
_YELLOW     = "\033[93m"    # tool call details (verbose)
_GRAY       = "\033[90m"    # stats / dim info
_CYAN       = "\033[96m"    # welcome / system messages

SEP  = "━" * 72
THIN = "─" * 72


def _c(code: str, text: str, use_colors: bool) -> str:
    return f"{code}{text}{_RESET}" if use_colors else text


# ── Startup helpers ───────────────────────────────────────────────────────────

def _lookup_source_type_id(executor: LiveExecutor) -> str:
    """Call list_source_types and return the user_input source type UUID."""
    result = executor.call("list_source_types", {})
    items = result.get("items", []) if isinstance(result, dict) else result
    for st in items:
        if st.get("name") == "user_input":
            return st["id"]
    raise RuntimeError(
        "Could not find 'user_input' source type in the DB.\n"
        "Ensure the DB is seeded (run schema migrations 01–07)."
    )


def _lookup_person(executor: LiveExecutor, person_id: str) -> tuple[str, str]:
    """Return (person_id, display_name) for the given person_id."""
    result = executor.call("get_person", {"person_id": person_id})
    if "error" in result or "id" not in result:
        raise RuntimeError(
            f"Person not found: {person_id}\n"
            f"Server response: {result}"
        )
    name = result.get("preferred_name") or result.get("full_name") or person_id
    return person_id, name


# ── Print helpers ─────────────────────────────────────────────────────────────

def _print_tool_calls(tool_names: list[str], use_colors: bool) -> None:
    if not tool_names:
        return
    header = _c(_YELLOW + _BOLD, f"  [{len(tool_names)} tool call{'s' if len(tool_names) != 1 else ''}]", use_colors)
    print(header)
    for name in tool_names:
        print(_c(_YELLOW, f"    → {name}", use_colors))


def _print_stats(stats: dict, use_colors: bool) -> None:
    ms   = stats.get("duration_api_ms", 0)
    inp  = stats.get("input_tokens", 0)
    hit  = stats.get("cache_read_tokens", 0)
    out  = stats.get("output_tokens", 0)
    nc   = stats.get("num_calls", 0)
    line = f"  [{nc} call{'s' if nc != 1 else ''} · {ms/1000:.1f}s · in={inp:,} hit={hit:,} out={out:,}]"
    print(_c(_GRAY, line, use_colors))


# ── REPL ──────────────────────────────────────────────────────────────────────

def run_repl(runner: AgenticRunner, person_name: str, verbose: bool, use_colors: bool) -> None:
    print()
    print(_c(_CYAN, SEP, use_colors))
    print(_c(_CYAN, f"  Personal Assistant  —  {person_name}", use_colors))
    print(_c(_CYAN, "  Type your message and press Enter. Ctrl-C or Ctrl-D to quit.", use_colors))
    print(_c(_CYAN, SEP, use_colors))
    print()

    while True:
        try:
            prompt_label = _c(_BLUE + _BOLD, "You: ", use_colors)
            user_input = input(prompt_label).strip()
        except (EOFError, KeyboardInterrupt):
            print("\n" + _c(_CYAN, "Goodbye.", use_colors))
            break

        if not user_input:
            continue

        print()

        try:
            assistant_text, tool_names, stats = runner.chat_turn(user_input, verbose=verbose)
        except Exception as exc:
            print(_c(_YELLOW, f"  [error] {exc}", use_colors))
            print()
            continue

        if verbose and tool_names:
            _print_tool_calls(tool_names, use_colors)
            print()

        if assistant_text:
            label = _c(_GREEN + _BOLD, "Assistant: ", use_colors)
            # Indent continuation lines so the label stands out.
            indented = assistant_text.replace("\n", "\n           ")
            print(f"{label}{_c(_GREEN, indented, use_colors)}")
        else:
            print(_c(_GRAY, "  [no text response]", use_colors))

        if verbose:
            print()
            _print_stats(stats, use_colors)

        print()


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Personal Assistant Chatbot")
    parser.add_argument(
        "--person-id",
        default=os.environ.get("CHATBOT_PERSON_ID"),
        help="UUID of the logged-in person (or set CHATBOT_PERSON_ID env var)",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Show tool calls and token stats between user and assistant messages",
    )
    parser.add_argument(
        "--no-color",
        action="store_true",
        help="Disable ANSI color output",
    )
    parser.add_argument(
        "--backend",
        choices=["bedrock", "claude-p"],
        default="bedrock",
        help="bedrock (default) or claude-p subprocess",
    )
    parser.add_argument(
        "--model",
        default=None,
        help="Claude model ID override",
    )
    args = parser.parse_args()

    if not args.person_id:
        print(
            "Error: --person-id is required (or set CHATBOT_PERSON_ID env var).",
            file=sys.stderr,
        )
        sys.exit(1)

    use_colors = not args.no_color and sys.stdout.isatty()

    # Chatbot defaults to the production DB; tests use myassistanttest.
    os.environ.setdefault("CHATBOT_DB", "myassistant")

    with managed_server() as base_url:
        token    = auth_token()
        executor = LiveExecutor(base_url=base_url, auth_token=token)

        try:
            # Resolve person name and source_type_id from the live DB.
            person_id, person_name = _lookup_person(executor, args.person_id)
            source_type_id = _lookup_source_type_id(executor)
        except RuntimeError as exc:
            print(f"Startup error: {exc}", file=sys.stderr)
            executor.close()
            sys.exit(1)

        system_prompt = build_system_prompt(person_id, person_name, source_type_id) + CHATBOT_PROMPT_ADDENDUM

        runner = AgenticRunner(
            executor      = executor,
            system_prompt = system_prompt,
            model         = args.model,
            backend       = args.backend,
            colors        = use_colors,
        )

        try:
            run_repl(runner, person_name, verbose=args.verbose, use_colors=use_colors)
        finally:
            executor.close()
