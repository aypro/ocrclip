"""DeepL API v2 text translation, ported from DeepL.kt. Free-plan keys end in ":fx" and use their own host."""

import json
import urllib.error
import urllib.request


class DeepLError(Exception):
    pass


def translate(text: str, key: str, target: str) -> str:
    """The translation of Japanese [text] into [target] (e.g. "EN-US"). Blocking."""
    host = "api-free.deepl.com" if key.endswith(":fx") else "api.deepl.com"
    body = json.dumps({"text": [text], "source_lang": "JA", "target_lang": target}).encode()
    request = urllib.request.Request(f"https://{host}/v2/translate", data=body, headers={
        "Content-Type": "application/json",
        "Authorization": f"DeepL-Auth-Key {key}",
    })
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read())["translations"][0]["text"]
    except urllib.error.HTTPError as e:
        raise DeepLError({403: "API key rejected", 456: "monthly quota used up", 429: "too many requests"}
                         .get(e.code, f"HTTP {e.code}")) from e
