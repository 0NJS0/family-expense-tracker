"""Minimal client for the local llama.cpp server (OpenAI-compatible chat API).

The model has no tools; its reply is only ever stored as text after the
numbers in it were checked. Standard library only.
"""

import json
import urllib.error
import urllib.request
from collections.abc import Callable

from app.config import Settings

# (system prompt, user prompt) -> reply text. Replaced by a fake in tests.
Generate = Callable[[str, str], str]


class LlmError(Exception):
    pass


def client(settings: Settings) -> Generate | None:
    """A generator for the configured server, or None when AI is off."""
    base = settings.llama_base_url
    if not base:
        return None

    def generate(system: str, user: str) -> str:
        body = {
            "model": settings.llama_model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.2,
            "max_tokens": 500,
            # Qwen3: answer directly, without a long hidden "thinking" pass.
            "chat_template_kwargs": {"enable_thinking": False},
        }
        headers = {"Content-Type": "application/json"}
        if settings.llama_api_key is not None:
            headers["Authorization"] = f"Bearer {settings.llama_api_key.get_secret_value()}"
        request = urllib.request.Request(  # noqa: S310 - configured http(s) URL only
            base.rstrip("/") + "/v1/chat/completions",
            data=json.dumps(body).encode(),
            headers=headers,
            method="POST",
        )
        if not request.full_url.startswith(("http://", "https://")):
            raise LlmError("llama_base_url must be http(s)")
        try:
            timeout = settings.llama_timeout_seconds
            opened = urllib.request.urlopen(request, timeout=timeout)  # noqa: S310 - scheme checked
            with opened as response:
                payload = json.loads(response.read())
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise LlmError(f"model server unavailable: {type(exc).__name__}") from exc
        try:
            text = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise LlmError("unexpected reply from the model server") from exc
        if not isinstance(text, str):
            raise LlmError("unexpected reply from the model server")
        return text

    return generate
