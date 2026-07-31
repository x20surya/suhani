"""Text-mode brain: the same agent as the voice path, minus ears and voice.

Used by the /api/chat debug endpoint and `python -m silai.brain_text` for a
terminal chat. This is where we break the agent with wrong names and missing
prices before any audio is involved.

Which LLM answers is decided by config.BRAIN:
  sarvam - Sarvam-105B in the bake-off's winning fixed/low config (launch brain)
  claude - Claude Sonnet 5 with prompt caching (fallback, one env-var away)
"""

import json
import sys

from . import config, prompts, tools
from .ledger import Ledger

MAX_TOOL_ROUNDS = 8


class ClaudeChatSession:
    def __init__(self, ledger: Ledger | None = None):
        import anthropic

        self.client = anthropic.Anthropic(api_key=config.ANTHROPIC_API_KEY)
        self.ledger = ledger or Ledger()
        self.messages: list[dict] = []

    def send(self, user_text: str) -> str:
        self.messages.append({"role": "user", "content": user_text})
        # Frozen prefix first (cached), then Suhani's notes (own breakpoint - stable
        # within a session), then the volatile date block last.
        system_blocks: list[dict] = [
            {
                "type": "text",
                "text": prompts.STABLE_SYSTEM,
                "cache_control": {"type": "ephemeral"},
            },
        ]
        notes = prompts.notes_context(self.ledger.list_notes())
        if notes:
            system_blocks.append(
                {"type": "text", "text": notes, "cache_control": {"type": "ephemeral"}}
            )
        system_blocks.append({"type": "text", "text": prompts.date_context()})
        for _ in range(MAX_TOOL_ROUNDS):
            response = self.client.messages.create(
                model=config.CLAUDE_MODEL,
                max_tokens=1024,
                system=system_blocks,
                tools=tools.ANTHROPIC_TOOLS,
                messages=self.messages,
                output_config={"effort": "low"},
            )
            self.messages.append({"role": "assistant", "content": response.content})
            if response.stop_reason != "tool_use":
                return "".join(b.text for b in response.content if b.type == "text")
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = tools.dispatch(self.ledger, block.name, dict(block.input))
                    tool_results.append(
                        {"type": "tool_result", "tool_use_id": block.id, "content": result}
                    )
            self.messages.append({"role": "user", "content": tool_results})
        return "माफ़ कीजिए, यह काम पूरा नहीं हो पाया। दोबारा बोलिए।"


class SarvamChatSession:
    """Sarvam-105B at fixed/low - guards + calendar table + resolve_due_date tool +
    1024-token budget + one deterministic retry on empty replies. This exact shape
    scored ~94-96% across ~1,300 bake-off calls; more effort tested WORSE."""

    def __init__(self, ledger: Ledger | None = None):
        from openai import OpenAI

        self.client = OpenAI(
            api_key=config.SARVAM_API_KEY,
            base_url=config.SARVAM_CHAT_BASE_URL,
            default_headers={"api-subscription-key": config.SARVAM_API_KEY},
        )
        self.ledger = ledger or Ledger()
        self.toolset = tools.openai_toolset()
        system = prompts.STABLE_SYSTEM + "\n\n" + prompts.SARVAM_GUARDS + "\n\n" + prompts.calendar_table()
        notes = prompts.notes_context(self.ledger.list_notes())
        if notes:
            system += "\n\n" + notes
        system += "\n\n" + prompts.date_context()
        self.messages: list[dict] = [{"role": "system", "content": system}]

    def _call(self):
        resp = self.client.chat.completions.create(
            model=config.SARVAM_CHAT_MODEL,
            messages=self.messages,
            tools=self.toolset,
            max_tokens=config.SARVAM_CHAT_MAX_TOKENS,
            extra_body={"reasoning_effort": config.SARVAM_CHAT_EFFORT},
        )
        return resp.choices[0].message

    def send(self, user_text: str) -> str:
        self.messages.append({"role": "user", "content": user_text})
        for _ in range(MAX_TOOL_ROUNDS):
            msg = self._call()
            if not msg.tool_calls and not (msg.content or "").strip():
                msg = self._call()  # one deterministic retry - dead air is worse than latency
            self.messages.append(msg.model_dump(exclude_none=True))
            if not msg.tool_calls:
                return msg.content or ""
            for tc in msg.tool_calls:
                args = json.loads(tc.function.arguments or "{}")
                result = tools.dispatch(self.ledger, tc.function.name, args)
                self.messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})
        return "माफ़ कीजिए, यह काम पूरा नहीं हो पाया। दोबारा बोलिए।"


ChatSession = SarvamChatSession if config.BRAIN == "sarvam" else ClaudeChatSession


def main() -> None:
    missing = config.missing_keys()
    if missing:
        print(f"इन keys के बिना नहीं चलेगा: {', '.join(missing)} - पहले .env में डालें।")
        sys.exit(1)
    session = ChatSession()
    print(f"सुहानी (text debug, brain={config.BRAIN}). Ctrl+C to exit.\n")
    try:
        while True:
            user = input("आप: ").strip()
            if not user:
                continue
            print("सुहानी:", session.send(user), "\n")
    except (KeyboardInterrupt, EOFError):
        print("\nनमस्ते!")


if __name__ == "__main__":
    main()
