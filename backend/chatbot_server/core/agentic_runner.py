import json
import os
import re
import subprocess
import threading
import time
from dataclasses import dataclass, field as dc_field
import struct, base64

from .tool_definitions import ALL_TOOLS


@dataclass
class TokenEvent:
    type: str = "token"
    text: str = ""

@dataclass
class ToolCallEvent:
    type: str = "tool_call"
    name: str = ""
    input: dict = dc_field(default_factory=dict)

@dataclass
class ToolResultEvent:
    type: str = "tool_result"
    tool_name: str = ""
    result: dict = dc_field(default_factory=dict)

@dataclass
class DoneEvent:
    type: str = "done"
    full_text: str = ""
    debug_info: dict = dc_field(default_factory=dict)


@dataclass
class ContextInfoEvent:
    type: str = "context_info"
    context_info: dict = dc_field(default_factory=dict)


@dataclass
class TopicSegment:
    topic: str
    size: int = 0           # number of _bedrock_messages belonging to this segment
    complete: bool = False  # True once a newer topic has started
    summarized: bool = False
    summary_text: str = ""

SEP  = "━" * 72
THIN = "─" * 72

# ANSI color codes
_RESET  = "\033[0m"
_YELLOW = "\033[93m"
_GRAY   = "\033[90m"


# ── Tool summary (claude-p backend only) ──────────────────────────────────────

def _agentic_tools_summary() -> str:
    lines = []
    for tool in ALL_TOOLS:
        schema = tool["input_schema"]
        props  = schema.get("properties", {})
        req    = set(schema.get("required", []))
        params = []
        for pname, spec in props.items():
            ptype = spec.get("type", "any")
            if "enum" in spec:
                ptype = "|".join(spec["enum"])
            marker = "*" if pname in req else ""
            params.append(f"{pname}{marker}:{ptype}")
        desc = tool["description"].split(".")[0].strip()
        lines.append(f"  {tool['name']}({', '.join(params)})")
        lines.append(f"    → {desc}")
    return "\n".join(lines)


_TOOLS_TEXT = _agentic_tools_summary()


# ── Bedrock tool list with prompt caching on the last entry ───────────────────

_BEDROCK_TOOLS = [
    {**t, "cache_control": {"type": "ephemeral"}} if i == len(ALL_TOOLS) - 1 else t
    for i, t in enumerate(ALL_TOOLS)
]


# ── History trimming ─────────────────────────────────────────────────────────

def _trim_base64_fields(obj, max_len: int = 200):
    """Recursively replace long base64 values with a size note."""
    if isinstance(obj, dict):
        return {
            k: (f"[{len(v)} chars base64 omitted]"
                if isinstance(v, str) and "base64" in k and len(v) > max_len
                else _trim_base64_fields(v, max_len))
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [_trim_base64_fields(item, max_len) for item in obj]
    return obj


def _trim_history_messages(messages: list[dict]) -> list[dict]:
    """
    Strip large base64 blobs from tool_result blocks before persisting to conversation history.
    The LLM already saw the full content in the same call; storing it forever bloats every
    subsequent request (a 1 MB image ≈ 350 K tokens re-sent on every turn).
    """
    result = []
    for msg in messages:
        if msg.get("role") != "user":
            result.append(msg)
            continue
        content = msg.get("content")
        if not isinstance(content, list):
            result.append(msg)
            continue
        new_blocks = []
        for block in content:
            if isinstance(block, dict) and block.get("type") == "tool_result":
                raw = block.get("content", "")
                if isinstance(raw, str) and len(raw) > 1000:
                    try:
                        trimmed = json.dumps(_trim_base64_fields(json.loads(raw)))
                        if len(trimmed) < len(raw):
                            block = {**block, "content": trimmed}
                    except (json.JSONDecodeError, TypeError):
                        pass
            new_blocks.append(block)
        result.append({**msg, "content": new_blocks})
    return result


# ── Stats helpers ─────────────────────────────────────────────────────────────

def _empty_totals() -> dict:
    return {
        "num_calls":              0,
        "duration_api_ms":        0,
        "input_tokens":           0,
        "cache_creation_tokens":  0,
        "cache_read_tokens":      0,
        "output_tokens":          0,
        "cost_usd":               0.0,
    }


def _accumulate(totals: dict, call_stats: dict) -> None:
    totals["num_calls"]             += 1
    totals["duration_api_ms"]       += call_stats.get("duration_api_ms", 0)
    totals["input_tokens"]          += call_stats.get("input_tokens", 0)
    totals["cache_creation_tokens"] += call_stats.get("cache_creation_tokens", 0)
    totals["cache_read_tokens"]     += call_stats.get("cache_read_tokens", 0)
    totals["output_tokens"]         += call_stats.get("output_tokens", 0)
    totals["cost_usd"]              += call_stats.get("cost_usd", 0.0)


def _fmt_call_stats(s: dict) -> str:
    cost = f"${s['cost_usd']:.4f}" if s["cost_usd"] else "(cost N/A)"
    return (
        f"{s['duration_api_ms']:,}ms  "
        f"in={s['input_tokens']:,}  "
        f"hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  "
        f"out={s['output_tokens']:,}  "
        f"{cost}"
    )


def _fmt_turn_stats(s: dict) -> str:
    n    = s["num_calls"]
    ms   = s["duration_api_ms"]
    cost = f"${s['cost_usd']:.4f}" if s["cost_usd"] else "(cost N/A)"
    return (
        f"{n} call{'s' if n != 1 else ''}  ·  {ms / 1000:.1f}s  ·  "
        f"in={s['input_tokens']:,}  hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  out={s['output_tokens']:,}  ·  "
        f"{cost}"
    )


def _fmt_scenario_stats(s: dict) -> str:
    nt   = s.get("num_turns", "?")
    nc   = s["num_calls"]
    ms   = s["duration_api_ms"]
    cost = f"${s['cost_usd']:.4f}" if s["cost_usd"] else "(cost N/A)"
    return (
        f"{nt} turn{'s' if nt != 1 else ''}  ·  "
        f"{nc} call{'s' if nc != 1 else ''}  ·  "
        f"{ms / 1000:.1f}s  ·  "
        f"in={s['input_tokens']:,}  hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  out={s['output_tokens']:,}  ·  "
        f"{cost}"
    )


# ── Verbose formatting helpers ────────────────────────────────────────────────

def _fmt_request_messages(messages: list[dict]) -> list[str]:
    id_to_name: dict[str, str] = {}
    for msg in messages:
        if msg["role"] == "assistant":
            for block in (msg.get("content") or []):
                if hasattr(block, "type") and block.type == "tool_use":
                    id_to_name[block.id] = block.name

    lines = []
    for msg in messages:
        role    = msg["role"]
        content = msg["content"]
        tag     = "user" if role == "user" else "asst"

        if isinstance(content, str):
            preview = content.replace("\n", " ")
            if len(preview) > 120:
                preview = preview[:117] + "..."
            lines.append(f"        {tag}  \"{preview}\"")
        elif isinstance(content, list):
            for block in content:
                if hasattr(block, "type"):
                    if block.type == "tool_use":
                        lines.append(f"        {tag}  [tool_use] {block.name}")
                    elif block.type == "text" and block.text.strip():
                        preview = block.text.replace("\n", " ")
                        if len(preview) > 120:
                            preview = preview[:117] + "..."
                        lines.append(f"        {tag}  [text] \"{preview}\"")
                elif isinstance(block, dict):
                    name    = id_to_name.get(block.get("tool_use_id", ""), "?")
                    raw     = block.get("content", "")
                    preview = raw[:100] + ("..." if len(raw) > 100 else "")
                    lines.append(f"        {tag}  [result] {name} → {preview}")
    return lines


def _fmt_response_blocks(content) -> list[str]:
    lines = []
    for block in (content or []):
        if not hasattr(block, "type"):
            continue
        if block.type == "tool_use":
            params = json.dumps(block.input)
            if len(params) > 120:
                params = params[:117] + "..."
            lines.append(f"        tool  {block.name}({params})")
        elif block.type == "text" and block.text.strip():
            text = block.text.replace("\n", " ")
            if len(text) > 200:
                text = text[:197] + "..."
            lines.append(f"        text  \"{text}\"")
    return lines


# ── Bedrock client ────────────────────────────────────────────────────────────

class _BedrockBearerClient:
    """
    Minimal Bedrock client using long-lived Bearer token auth (BEDROCK_API_KEY).
    Interface mirrors AnthropicBedrock so AgenticRunner needs no changes.
    """

    def __init__(self, api_key: str, region: str):
        import httpx
        self._api_key = api_key
        self._region  = region
        self._http    = httpx.Client(timeout=120)
        self.messages = self

    def create(self, *, model, max_tokens, system, tools, messages):
        import anthropic
        url = (
            f"https://bedrock-runtime.{self._region}.amazonaws.com"
            f"/model/{model}/invoke"
        )

        def _ser(content):
            if isinstance(content, str):
                return [{"type": "text", "text": content}]
            if not isinstance(content, list):
                return content
            result = []
            for item in content:
                if isinstance(item, dict):
                    result.append(item)
                elif hasattr(item, "model_dump"):
                    result.append(item.model_dump(exclude_none=True))
                else:
                    result.append({"type": "text", "text": str(item)})
            return result

        body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": max_tokens,
            "system":   _ser(system),
            "tools":    tools,
            "messages": [{"role": m["role"], "content": _ser(m["content"])} for m in messages],
        }
        resp = self._http.post(url, json=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self._api_key}",
        })
        resp.raise_for_status()
        return anthropic.types.Message.model_validate(resp.json())

    def stream(self, *, model, max_tokens, system, tools, messages):
        """Call invoke-with-response-stream; yield decoded Anthropic event dicts."""
        url = (
            f"https://bedrock-runtime.{self._region}.amazonaws.com"
            f"/model/{model}/invoke-with-response-stream"
        )

        def _ser(content):
            if isinstance(content, str):
                return [{"type": "text", "text": content}]
            if not isinstance(content, list):
                return content
            result = []
            for item in content:
                if isinstance(item, dict):
                    result.append(item)
                elif hasattr(item, "model_dump"):
                    result.append(item.model_dump(exclude_none=True))
                else:
                    result.append({"type": "text", "text": str(item)})
            return result

        body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": max_tokens,
            "system": _ser(system),
            "tools": tools,
            "messages": [{"role": m["role"], "content": _ser(m["content"])} for m in messages],
        }
        buf = b""
        with self._http.stream("POST", url, json=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self._api_key}",
        }) as response:
            if not response.is_success:
                detail = response.read().decode(errors="replace")[:600]
                raise RuntimeError(f"Bedrock {response.status_code}: {detail}")
            for chunk in response.iter_bytes(4096):
                buf += chunk
                while len(buf) >= 12:
                    total_len = struct.unpack_from(">I", buf, 0)[0]
                    if len(buf) < total_len:
                        break
                    headers_len = struct.unpack_from(">I", buf, 4)[0]
                    payload_start = 12 + headers_len
                    payload_end   = total_len - 4
                    payload_bytes = buf[payload_start:payload_end]
                    buf = buf[total_len:]
                    try:
                        wrapper = json.loads(payload_bytes)
                        if "bytes" in wrapper:
                            yield json.loads(base64.b64decode(wrapper["bytes"]))
                    except Exception:
                        pass


def _make_bedrock_client(region: str | None = None):
    r = region or os.environ.get("AWS_REGION", "us-west-2")
    api_key = os.environ.get("BEDROCK_API_KEY")
    if api_key:
        return _BedrockBearerClient(api_key=api_key, region=r)
    try:
        import anthropic
        return anthropic.AnthropicBedrock(aws_region=r)
    except ImportError:
        raise RuntimeError(
            "boto3/botocore not installed. Run: pip install boto3\n"
            "Or set BEDROCK_API_KEY to use Bearer token auth instead."
        )


# ── claude-p subprocess helpers ───────────────────────────────────────────────

def _extract_json_array(text: str) -> list | None:
    text = re.sub(r"```(?:json)?\s*", "", text)
    text = re.sub(r"```", "", text)
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        return None
    try:
        return json.loads(match.group())
    except json.JSONDecodeError:
        return None


def _call_claude(prompt: str, verbose: bool = False) -> tuple[list | None, str | None, dict | None]:
    try:
        result = subprocess.run(
            ["claude", "-p", "--output-format", "json", prompt],
            capture_output=True, text=True, timeout=120,
        )
    except subprocess.TimeoutExpired:
        return None, "Timed out after 120s", None
    except FileNotFoundError:
        return None, "'claude' not found in PATH", None

    if result.returncode != 0:
        err = (result.stderr or result.stdout or "").strip()[:300]
        return None, f"claude exited {result.returncode}: {err}", None

    try:
        envelope = json.loads(result.stdout)
    except json.JSONDecodeError:
        snippet = result.stdout.strip()[:400]
        return None, f"Could not parse JSON envelope:\n{snippet}", None

    if envelope.get("is_error") or envelope.get("subtype") != "success":
        err = str(envelope.get("result", envelope))[:300]
        return None, f"claude error: {err}", None

    usage = envelope.get("usage", {})
    call_stats = {
        "duration_api_ms":       envelope.get("duration_api_ms", 0),
        "input_tokens":          usage.get("input_tokens", 0),
        "cache_creation_tokens": usage.get("cache_creation_input_tokens", 0),
        "cache_read_tokens":     usage.get("cache_read_input_tokens", 0),
        "output_tokens":         usage.get("output_tokens", 0),
        "cost_usd":              envelope.get("total_cost_usd", 0.0),
    }

    response_text = envelope.get("result", "")
    if verbose:
        print(f"\n  [RAW] {response_text[:300]}")

    calls = _extract_json_array(response_text)
    if calls is None:
        return None, f"Could not parse tool calls:\n{response_text[:400]}", call_stats

    return calls, None, call_stats


# ── bedrock_plan_scenario (for test harness mock-plan mode) ──────────────────

def bedrock_plan_scenario(
    scenario: dict,
    system_prompt: str,
    model: str = "us.anthropic.claude-sonnet-4-6",
    verbose: bool = False,
    executor=None,
) -> tuple[list[list[dict]] | None, str | None]:
    """
    Run mock-plan for all turns using Bedrock native tool use.
    Returns (list_of_turn_call_lists, error).
    """
    bedrock_system = [{"type": "text", "text": system_prompt, "cache_control": {"type": "ephemeral"}}]
    client = _make_bedrock_client()
    all_turn_calls: list[list[dict]] = []
    messages:       list[dict]       = []
    call_num = 0

    for turn_idx, turn in enumerate(scenario["turns"]):
        messages.append({"role": "user", "content": turn["user_message"]})
        turn_calls: list[dict] = []

        while True:
            call_num += 1
            try:
                t0       = time.perf_counter()
                response = client.messages.create(
                    model      = model,
                    max_tokens = 4096,
                    system     = bedrock_system,
                    tools      = _BEDROCK_TOOLS,
                    messages   = messages,
                )
                duration_ms = int((time.perf_counter() - t0) * 1000)
            except Exception as exc:
                return None, f"Turn {turn_idx + 1}: {exc}"

            if verbose:
                u = response.usage
                print(
                    f"  [turn {turn_idx + 1} call {call_num}] {duration_ms:,}ms  "
                    f"in={u.input_tokens}  "
                    f"hit={getattr(u, 'cache_read_input_tokens', 0)}  "
                    f"new={getattr(u, 'cache_creation_input_tokens', 0)}  "
                    f"out={u.output_tokens}"
                )

            tool_blocks = [b for b in response.content if b.type == "tool_use"]
            for b in tool_blocks:
                turn_calls.append({"tool": b.name, "params": b.input})

            if response.stop_reason != "tool_use" or not tool_blocks or executor is None:
                messages.append({"role": "assistant", "content": response.content})
                break

            tool_results = []
            for block in tool_blocks:
                try:
                    result = executor.call(block.name, block.input)
                except Exception as exc:
                    result = {"error": str(exc)}
                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block.id,
                    "content":     json.dumps(result),
                })
            messages.append({"role": "assistant", "content": response.content})
            messages.append({"role": "user",      "content": tool_results})

        all_turn_calls.append(turn_calls)

    return all_turn_calls, None


# ── AgenticRunner ─────────────────────────────────────────────────────────────

class AgenticRunner:
    """
    Agentic loop runner.

    backend="bedrock"  — Anthropic SDK via AWS Bedrock (default).
    backend="claude-p" — claude -p subprocess loop.

    system_prompt: injected at construction — allows tests and chatbot to use
                   different prompts without code changes.
    global_forbidden: tool names that must never appear (test harness only).
    colors: if True, verbose output uses ANSI color codes (for chatbot display).
    """

    def __init__(
        self,
        executor,
        system_prompt: str,
        model: str | None = None,
        backend: str = "bedrock",
        global_forbidden: list[str] | None = None,
        colors: bool = False,
        person_id: str | None = None,
    ):
        self._executor         = executor
        self._system_prompt    = system_prompt
        self._backend          = backend
        self._global_forbidden = global_forbidden or []
        self._colors           = colors
        self._person_id        = person_id

        self._bedrock_system = [
            {"type": "text", "text": system_prompt, "cache_control": {"type": "ephemeral"}}
        ]

        if backend == "bedrock":
            self._model   = model or "us.anthropic.claude-sonnet-4-6"
            self._bedrock = _make_bedrock_client()
        else:
            self._model   = model or "claude-sonnet-4-6"
            self._bedrock = None

        # Persistent message history (bedrock) and chat prior-turns (claude-p).
        self._bedrock_messages:  list[dict] = []
        self._chat_prior_turns:  list[dict] = []

        # Context summarization state.
        self._min_raw_turns:         int                 = int(os.environ.get("CHATBOT_MIN_RAW_TURNS", "5"))
        self._current_topic:         str                 = ""
        self._topic_segments:        list[TopicSegment]  = []
        self._summary_msg_count:     int                 = 0
        self._bg_thread:             threading.Thread | None = None
        self._context_info:          dict                = {}
        self._new_summaries_pending: list[dict]          = []

    # ── Public API ────────────────────────────────────────────────────────

    def run_scenario(
        self, scenario: dict, verbose: bool = False
    ) -> tuple[list[list[str]] | None, dict | None, str | None]:
        """
        Run all turns of a test scenario.
        Returns (list_of_tool_name_lists_per_turn, scenario_stats, error).
        """
        if hasattr(self._executor, "set_scenario"):
            self._executor.set_scenario(scenario)

        self._bedrock_messages = []

        prior_turns:         list[dict]      = []
        all_turn_tool_names: list[list[str]] = []
        all_turn_stats:      list[dict]      = []
        scenario_total = _empty_totals()

        for turn_idx, turn in enumerate(scenario["turns"]):
            try:
                tool_names, turn_stats = self._run_turn(turn["user_message"], prior_turns, verbose)
            except Exception as exc:
                return None, None, f"Turn {turn_idx + 1}: {exc}"

            all_turn_tool_names.append(tool_names)
            all_turn_stats.append(turn_stats)

            for key in ("num_calls", "duration_api_ms", "input_tokens",
                        "cache_creation_tokens", "cache_read_tokens", "output_tokens"):
                scenario_total[key] += turn_stats[key]
            scenario_total["cost_usd"] += turn_stats["cost_usd"]

            prior_turns.append({"user_message": turn["user_message"], "tool_names": tool_names})

        scenario_total["num_turns"] = len(scenario["turns"])
        return (
            all_turn_tool_names,
            {"turns": all_turn_stats, "total": scenario_total},
            None,
        )

    def chat_turn(
        self, user_message: str, verbose: bool = False
    ) -> tuple[str, list[str], dict]:
        """
        Run one interactive chatbot turn.
        Maintains full conversation history across calls.
        Returns (assistant_text, tool_names_called, turn_stats).
        """
        tool_names, stats = self._run_turn(user_message, self._chat_prior_turns, verbose)
        self._chat_prior_turns.append({"user_message": user_message, "tool_names": tool_names})
        return self._extract_last_response_text(), tool_names, stats

    # ── Context summarization ─────────────────────────────────────────────────

    def _detect_topic_change(self, user_msg: str) -> tuple[bool, str]:
        """Micro-call (~50 max_tokens). Returns (changed, new_topic_label)."""
        if not self._current_topic:
            prompt = (
                f"Name this conversation topic in 5 words or fewer. "
                f"User said: '{user_msg[:300]}'. "
                f"JSON only: {{\"topic\": \"label\"}}"
            )
            first_turn = True
        else:
            prompt = (
                f"Previous topic: '{self._current_topic}'. "
                f"User just said: '{user_msg[:300]}'. "
                f"Has the topic changed? "
                f"JSON only: {{\"changed\": true, \"topic\": \"label\"}} or {{\"changed\": false, \"topic\": \"same label\"}}"
            )
            first_turn = False

        resp = self._bedrock.messages.create(
            model=self._model,
            max_tokens=50,
            system=[{"type": "text", "text": "You are a conversation topic classifier. Respond in JSON only. Do not call any tools."}],
            tools=_BEDROCK_TOOLS,
            messages=[{"role": "user", "content": prompt}],
        )
        try:
            text = next((b.text for b in resp.content if hasattr(b, "text")), "")
            # Strip markdown fences if the LLM wrapped the JSON
            text = re.sub(r"```(?:json)?\s*|\s*```", "", text).strip()
            data = json.loads(text)
            new_topic = data.get("topic", self._current_topic) or self._current_topic
            changed = True if first_turn else bool(data.get("changed", False))
            return changed, new_topic
        except Exception:
            return False, self._current_topic or "General"

    def _summarize_segment(self, topic: str, messages: list[dict], prior_summaries: list[dict] | None = None) -> str:
        """Summarize messages into 2-3 sentences. prior_summaries provides running context."""
        lines = []
        for m in messages:
            role    = m.get("role", "")
            content = m.get("content", "")
            if isinstance(content, str):
                lines.append(f"{role}: {content[:400]}")
            elif isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "text":
                        lines.append(f"{role}: {block['text'][:400]}")
        transcript = "\n".join(lines)

        if prior_summaries:
            prior_block = "\n".join(
                f"[{s['topic']}]: {s['summaryText']}" for s in prior_summaries
            )
            content = (
                f"PRIOR CONVERSATION SUMMARIES (for context):\n{prior_block}\n\n"
                f"SEGMENT TO SUMMARIZE NOW ({topic}):\n{transcript or '[no text content]'}"
            )
        else:
            content = transcript or f"[{topic} — no text content]"

        resp = self._bedrock.messages.create(
            model=self._model,
            max_tokens=200,
            system=[{"type": "text", "text": "Summarize the given conversation segment in 2-3 sentences. Use prior summaries only as background context — do not re-summarize them. Preserve key facts, decisions, and actions. Be concise. Do not call any tools."}],
            tools=_BEDROCK_TOOLS,
            messages=[{"role": "user", "content": content}],
        )
        try:
            return next((b.text.strip() for b in resp.content if hasattr(b, "text")), f"[Summary unavailable for: {topic}]")
        except Exception:
            return f"[Summary unavailable for: {topic}]"

    def _is_human_user_message(self, m: dict) -> bool:
        """True for actual human chat messages — excludes tool-result and summary messages."""
        if m.get("role") != "user":
            return False
        content = m.get("content", "")
        # Tool-result messages have list content; summaries start with the CONTEXT SUMMARY prefix.
        if not isinstance(content, str):
            return False
        return not content.startswith("[CONTEXT SUMMARY")

    def _count_raw_user_turns(self) -> int:
        """Count human user messages in the non-summarized tail of _bedrock_messages."""
        return sum(
            1 for m in self._bedrock_messages[self._summary_msg_count:]
            if self._is_human_user_message(m)
        )

    def _find_segment_end(self, start_idx: int, num_turns: int) -> int:
        """Return the index just past the last bedrock message of num_turns user turns."""
        turns_seen = 0
        i = start_idx
        while i < len(self._bedrock_messages):
            if self._is_human_user_message(self._bedrock_messages[i]):
                turns_seen += 1
                if turns_seen == num_turns:
                    i += 1
                    # Advance past assistant replies and tool-result round-trips
                    while i < len(self._bedrock_messages) and not self._is_human_user_message(self._bedrock_messages[i]):
                        i += 1
                    return i
            i += 1
        return len(self._bedrock_messages)

    def _check_and_summarize(self) -> None:
        """
        Greedily summarize the oldest complete segments while
        (raw_user_turns - segment.size) >= _min_raw_turns.
        segment.size counts user turns (one per human message), not bedrock messages.
        """
        raw_count = self._count_raw_user_turns()
        pos = self._summary_msg_count  # current scan position in _bedrock_messages

        for seg in self._topic_segments:
            if seg.summarized:
                # Already a single summary message at pos — skip it
                pos += 1
                continue
            if not seg.complete:
                break
            if raw_count - seg.size < self._min_raw_turns:
                break

            end_at = self._find_segment_end(pos, seg.size)
            msgs   = self._bedrock_messages[pos:end_at]

            # Pass already-summarized segments as running context.
            prior_summaries = [
                {"topic": s.topic, "summaryText": s.summary_text}
                for s in self._topic_segments if s.summarized
            ]
            summary_text = self._summarize_segment(seg.topic, msgs, prior_summaries or None)
            summary_msg  = {
                "role":    "user",
                "content": f"[CONTEXT SUMMARY — {seg.topic}]: {summary_text}",
            }
            self._bedrock_messages[pos:end_at] = [summary_msg]

            seg.summarized   = True
            seg.summary_text = summary_text
            self._summary_msg_count += 1
            raw_count -= seg.size
            pos += 1  # advance past the summary message we just inserted

            self._new_summaries_pending.append({
                "topic":        seg.topic,
                "summaryText":  summary_text,
                "messageCount": seg.size,
            })

    def _background_topic_and_summarize(self, user_msg: str) -> None:
        """Runs in a daemon thread after each turn's done event is yielded."""
        try:
            changed, new_topic = self._detect_topic_change(user_msg)

            if not self._topic_segments:
                self._topic_segments.append(TopicSegment(topic=new_topic, size=1))
            elif changed:
                self._topic_segments[-1].complete = True
                self._topic_segments.append(TopicSegment(topic=new_topic, size=1))
            else:
                self._topic_segments[-1].size += 1
            self._current_topic = new_topic

            if self._count_raw_user_turns() > self._min_raw_turns:
                self._check_and_summarize()

            raw_turn_count    = self._count_raw_user_turns()
            summarized_topics = [
                {"topic": s.topic, "summaryText": s.summary_text, "messageCount": s.size}
                for s in self._topic_segments if s.summarized
            ]
            all_segments = [
                {
                    "topic":        s.topic,
                    "messageCount": s.size,
                    "summarized":   s.summarized,
                    "complete":     s.complete,
                    "summaryText":  s.summary_text,
                }
                for s in self._topic_segments
            ]
            self._context_info = {
                "currentTopic":         self._current_topic,
                "rawTurnCount":         raw_turn_count,
                "summarizedTopics":     summarized_topics,
                "allSegments":          all_segments,
                "newSummariesThisTurn": len(self._new_summaries_pending),
                "newSummaries":         list(self._new_summaries_pending),
            }
            self._new_summaries_pending.clear()
        except Exception:
            pass  # bg failures are silent — never block the user turn

    # ── Streaming public API ──────────────────────────────────────────────────

    def chat_turn_streaming(self, user_message: str):
        """
        Sync generator — intended to run in a thread pool from async FastAPI code.
        Yields: TokenEvent | ToolCallEvent | ToolResultEvent | DoneEvent.
        Maintains the same _bedrock_messages conversation history as chat_turn().
        """
        all_tool_calls: list[dict] = []
        api_calls:      list[dict] = []
        full_text = ""

        messages = list(self._bedrock_messages)
        messages.append({"role": "user", "content": user_message})

        while True:
            accumulated_text   = ""
            current_tool_use   = None
            current_input_json = ""
            response_content:  list[dict] = []
            stop_reason        = "end_turn"
            usage_stats:       dict = {}

            t0 = time.perf_counter()

            if isinstance(self._bedrock, _BedrockBearerClient):
                raw_events = self._bedrock.stream(
                    model=self._model, max_tokens=4096,
                    system=self._bedrock_system, tools=_BEDROCK_TOOLS,
                    messages=messages,
                )
            else:
                # anthropic.AnthropicBedrock — native SDK streaming
                sdk_stream = self._bedrock.messages.stream(
                    model=self._model, max_tokens=4096,
                    system=self._bedrock_system, tools=_BEDROCK_TOOLS,
                    messages=messages,
                )
                raw_events = (e.model_dump() for e in sdk_stream.__enter__())

            for event in raw_events:
                etype = event.get("type")

                if etype == "message_start":
                    u = event.get("message", {}).get("usage", {})
                    usage_stats["inputTokens"]      = u.get("input_tokens", 0)
                    usage_stats["cacheReadTokens"]   = u.get("cache_read_input_tokens", 0)

                elif etype == "content_block_start":
                    blk = event.get("content_block", {})
                    if blk.get("type") == "tool_use":
                        current_tool_use   = {"id": blk["id"], "name": blk["name"]}
                        current_input_json = ""

                elif etype == "content_block_delta":
                    delta = event.get("delta", {})
                    if delta.get("type") == "text_delta":
                        text = delta["text"]
                        accumulated_text += text
                        full_text        += text
                        yield TokenEvent(text=text)
                    elif delta.get("type") == "input_json_delta":
                        current_input_json += delta.get("partial_json", "")

                elif etype == "content_block_stop":
                    if current_tool_use is not None:
                        try:
                            inp = json.loads(current_input_json) if current_input_json else {}
                        except json.JSONDecodeError:
                            inp = {}
                        current_tool_use["input"] = inp
                        response_content.append({"type": "tool_use", **current_tool_use})
                        current_tool_use   = None
                        current_input_json = ""
                    elif accumulated_text:
                        response_content.append({"type": "text", "text": accumulated_text})
                        accumulated_text = ""

                elif etype == "message_delta":
                    delta = event.get("delta", {})
                    stop_reason = delta.get("stop_reason", "end_turn")
                    u = event.get("usage", {})
                    usage_stats["outputTokens"] = u.get("output_tokens", 0)

            duration_ms = int((time.perf_counter() - t0) * 1000)
            api_calls.append({
                "requestMessages": [{"role": m["role"]} for m in messages],
                "responseBlocks":  response_content,
                "stats": {
                    "durationMs":       duration_ms,
                    "inputTokens":      usage_stats.get("inputTokens", 0),
                    "cacheReadTokens":  usage_stats.get("cacheReadTokens", 0),
                    "outputTokens":     usage_stats.get("outputTokens", 0),
                },
            })

            messages.append({"role": "assistant", "content": response_content})

            tool_blocks = [b for b in response_content if b.get("type") == "tool_use"]
            if stop_reason != "tool_use" or not tool_blocks:
                break

            tool_results = []
            for block in tool_blocks:
                name   = block["name"]
                params = block["input"]
                yield ToolCallEvent(name=name, input=params)
                try:
                    result = self._executor.call(name, params)
                except Exception as exc:
                    result = {"error": str(exc)}
                all_tool_calls.append({"name": name, "input": params, "result": result})
                yield ToolResultEvent(tool_name=name, result=result)
                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block["id"],
                    "content":     json.dumps(result),
                })
            messages.append({"role": "user", "content": tool_results})

        self._bedrock_messages = _trim_history_messages(messages)

        self._call_log_interaction(
            user_message, full_text,
            [{"tool": t["name"], "params": t["input"]} for t in all_tool_calls],
        )
        yield DoneEvent(
            full_text=full_text,
            debug_info={"apiCalls": api_calls, "toolCalls": all_tool_calls},
        )

        # Run topic detection + summarization in a thread, then push the result
        # on the same SSE stream so the frontend updates without waiting for the next turn.
        if self._bedrock is not None:
            self._bg_thread = threading.Thread(
                target=self._background_topic_and_summarize,
                args=(user_message,),
                daemon=True,
            )
            self._bg_thread.start()
            self._bg_thread.join()
            if self._context_info:
                yield ContextInfoEvent(context_info=dict(self._context_info))
                self._context_info = {}

    def _extract_last_response_text(self) -> str:
        """Pull the text content from the last assistant message in _bedrock_messages."""
        if not self._bedrock_messages:
            return ""
        last = self._bedrock_messages[-1]
        if last.get("role") != "assistant":
            return ""
        parts = []
        for block in (last.get("content") or []):
            if hasattr(block, "type") and block.type == "text" and block.text.strip():
                parts.append(block.text)
            elif isinstance(block, dict) and block.get("type") == "text":
                t = block.get("text", "").strip()
                if t:
                    parts.append(t)
        return "\n".join(parts)

    # ── Internal dispatch ─────────────────────────────────────────────────

    def _run_turn(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        if self._backend == "bedrock":
            return self._run_turn_bedrock(user_message, prior_turns, verbose)
        return self._run_turn_claude_p(user_message, prior_turns, verbose)

    def _vprint(self, msg: str) -> None:
        """Verbose print — yellow when colors enabled."""
        if self._colors:
            print(f"{_YELLOW}{msg}{_RESET}")
        else:
            print(msg)

    def _vprint_gray(self, msg: str) -> None:
        if self._colors:
            print(f"{_GRAY}{msg}{_RESET}")
        else:
            print(msg)

    # ── Bedrock backend ───────────────────────────────────────────────────

    def _run_turn_bedrock(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        tool_names:  list[str]  = []
        tool_calls:  list[dict] = []
        turn_totals = _empty_totals()
        call_num    = 0

        messages: list[dict] = list(self._bedrock_messages)
        messages.append({"role": "user", "content": user_message})

        while True:
            call_num += 1

            if verbose:
                n = len(messages)
                self._vprint(f"\n    [call {call_num}]")
                self._vprint(f"      request  ({n} message{'s' if n != 1 else ''})")
                for line in _fmt_request_messages(messages):
                    self._vprint(line)

            t0 = time.perf_counter()
            response = self._bedrock.messages.create(
                model      = self._model,
                max_tokens = 4096,
                system     = self._bedrock_system,
                tools      = _BEDROCK_TOOLS,
                messages   = messages,
            )
            duration_ms = int((time.perf_counter() - t0) * 1000)

            usage = response.usage
            call_stats = {
                "duration_api_ms":       duration_ms,
                "input_tokens":          usage.input_tokens,
                "cache_creation_tokens": getattr(usage, "cache_creation_input_tokens", 0),
                "cache_read_tokens":     getattr(usage, "cache_read_input_tokens", 0),
                "output_tokens":         usage.output_tokens,
                "cost_usd":              0.0,
            }
            _accumulate(turn_totals, call_stats)

            if verbose:
                self._vprint(f"      response  {_fmt_call_stats(call_stats)}")
                for line in _fmt_response_blocks(response.content):
                    self._vprint(line)

            tool_blocks = [b for b in response.content if b.type == "tool_use"]

            if response.stop_reason != "tool_use" or not tool_blocks:
                messages.append({"role": "assistant", "content": response.content})
                break

            tool_results = []
            for block in tool_blocks:
                name   = block.name
                params = block.input
                tool_names.append(name)
                tool_calls.append({"tool": name, "params": params})
                try:
                    result = self._executor.call(name, params)
                except Exception as exc:
                    result = {"error": str(exc)}
                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block.id,
                    "content":     json.dumps(result),
                })

            messages.append({"role": "assistant", "content": response.content})
            messages.append({"role": "user",      "content": tool_results})

        self._bedrock_messages = _trim_history_messages(messages)
        self._call_log_interaction(user_message, self._extract_last_response_text(), tool_calls)
        return tool_names, turn_totals

    # ── claude-p backend ──────────────────────────────────────────────────

    def _run_turn_claude_p(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        tool_names:   list[str]  = []
        step_results: list[dict] = []
        turn_totals = _empty_totals()
        call_num = 0

        while True:
            prompt = self._build_step_prompt(user_message, prior_turns, step_results)
            calls, error, call_stats = _call_claude(prompt, verbose)
            call_num += 1

            if call_stats:
                _accumulate(turn_totals, call_stats)
                if verbose:
                    self._vprint(f"  [call {call_num}] {_fmt_call_stats(call_stats)}")

            if error:
                raise RuntimeError(error)
            if not calls:
                break

            for call in calls:
                name   = call.get("tool", call.get("name", "?"))
                params = call.get("params", call.get("parameters", call.get("input", {})))
                tool_names.append(name)
                if verbose:
                    self._vprint(f"  [tool] {name}({json.dumps(params)[:120]})")
                try:
                    result = self._executor.call(name, params)
                except Exception as exc:
                    result = {"error": str(exc)}
                step_results.append({"tool": name, "params": params, "result": result})

        tool_calls = [{"tool": s["tool"], "params": s["params"]} for s in step_results]
        self._call_log_interaction(user_message, "[agent]", tool_calls)
        return tool_names, turn_totals

    def _build_step_prompt(
        self,
        user_message: str,
        prior_turns:  list[dict],
        step_results: list[dict],
    ) -> str:
        parts = [self._system_prompt]
        parts.append(f"\n{'─' * 72}")
        parts.append(f"TOOLS ({len(ALL_TOOLS)} total)\nParameters marked * are required.")
        parts.append(_TOOLS_TEXT)

        if prior_turns:
            parts.append(f"\n{'─' * 72}")
            parts.append("CONVERSATION HISTORY")
            for i, t in enumerate(prior_turns, 1):
                parts.append(f"\nTurn {i}")
                parts.append(f'User: "{t["user_message"]}"')
                parts.append(f"Tools called: {json.dumps(t['tool_names'])}")

        parts.append(f"\n{'─' * 72}")
        parts.append("CURRENT TURN")
        parts.append(f'User: "{user_message}"')

        if step_results:
            parts.append("\nPrevious steps this turn (already executed):")
            for i, s in enumerate(step_results, 1):
                params_str = json.dumps(s["params"])[:100]
                result_str = json.dumps(s["result"])[:300]
                parts.append(f"  Step {i}: {s['tool']}({params_str}) → {result_str}")

        parts.append(
            "\nOutput ONLY a JSON array of the next tool calls to make, "
            "or [] if you are done:\n"
            '[{"tool": "tool_name", "params": {"param*": "value"}}, ...]\n'
            "Output only the JSON array — no explanation, no markdown fences."
        )
        return "\n".join(parts)

    # ── Logging ───────────────────────────────────────────────────────────

    def _call_log_interaction(
        self,
        user_message:  str,
        response_text: str,
        tool_calls:    list[dict],
    ) -> None:
        params: dict = {
            "message_text":   user_message,
            "response_text":  response_text,
            "status":         "success",
            "tool_calls_json": tool_calls or [],
        }
        if self._person_id is not None:
            params["person_id"] = self._person_id
        else:
            params["job_type"] = "chatbot"
        try:
            self._executor.call("log_interaction", params)
        except Exception:
            pass

    # ── Test harness output ───────────────────────────────────────────────

    def print_result(
        self,
        scenario:            dict,
        all_turn_tool_names: list[list[str]] | None,
        scenario_stats:      dict | None,
        error:               str | None,
    ) -> None:
        """Print full test-harness result with validation. Uses self._global_forbidden."""
        turns = scenario["turns"]
        scenario_forbidden = scenario.get("forbidden_tools", [])
        global_forbidden   = list(dict.fromkeys(self._global_forbidden + scenario_forbidden))

        print(f"\n{SEP}")
        print(f"  {scenario['name']}")
        print(SEP)

        if error:
            print(f"  ERROR: {error}")
            print()
            return

        scenario_pass = True

        for turn_idx, (turn, tool_names) in enumerate(zip(turns, all_turn_tool_names)):
            n_turns = len(turns)
            print(f"\n  ── Turn {turn_idx + 1} of {n_turns} " + "─" * (53 - len(str(n_turns))))
            print(f"  USER: \"{turn['user_message']}\"")
            print()
            print(f"  TOOL CALLS  ({len(tool_names)} total)")
            print(f"  {THIN}")
            for n, name in enumerate(tool_names, 1):
                print(f"  {n:2d}. {name}")
            print()

            if scenario_stats and turn_idx < len(scenario_stats["turns"]):
                ts = scenario_stats["turns"][turn_idx]
                print(f"  TURN STATS   {_fmt_turn_stats(ts)}")
                print()

            expected       = turn.get("expected_tools", [])
            turn_forbidden = turn.get("forbidden_tools", [])
            all_forbidden  = list(dict.fromkeys(global_forbidden + turn_forbidden))

            if expected or all_forbidden:
                turn_ok = True
                label = f"Turn {turn_idx + 1}" if n_turns > 1 else ""
                print(f"  VALIDATION{' — ' + label if label else ''}")
                print(f"  {THIN}")
                for exp in expected:
                    found = exp in tool_names
                    if not found:
                        turn_ok = False
                        scenario_pass = False
                    print(f"    {'✓' if found else '✗'}  {exp}")
                for forb in all_forbidden:
                    present = forb in tool_names
                    if present:
                        turn_ok = False
                        scenario_pass = False
                    print(f"    {'✗ SHOULD NOT appear:' if present else '✓ (absent)'}  {forb}")
                extra = [nm for nm in tool_names if nm not in expected and nm not in all_forbidden]
                for nm in extra:
                    print(f"    ?  {nm}  (not in expected list — may be fine)")
                print(f"    → {'all checks passed ✓' if turn_ok else 'ISSUES FOUND'}")
                print()

        if scenario_stats:
            print(f"  SCENARIO STATS   {_fmt_scenario_stats(scenario_stats['total'])}")
        status = "PASS ✓" if scenario_pass else "FAIL ✗"
        print(f"  SCENARIO {status}")
