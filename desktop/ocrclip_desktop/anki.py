"""
Anki through AnkiConnect (desktop Anki's add-on, in place of AnkiDroid's content provider in Anki.kt), and word
recordings from the Local Audio Server for Yomichan add-on running in the same Anki.
"""

import base64
import json
import urllib.error
import urllib.parse
import urllib.request


SYNC_TIMEOUT = 300


class AnkiError(Exception):
    pass


class AudioUnavailable(Exception):
    """The audio server couldn't be asked, as opposed to having no recording of the word."""


class Anki:
    def __init__(self, config):
        self.config = config

    def invoke(self, action: str, timeout: float = 10, **params):
        body = json.dumps({"action": action, "version": 6, "params": params}).encode()
        request = urllib.request.Request(self.config["anki_connect"], data=body,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                reply = json.loads(response.read())
        except TimeoutError as e:  # connected, but no answer in time
            raise AnkiError("Anki didn't answer in time") from e
        except (urllib.error.URLError, ConnectionError) as e:
            if isinstance(getattr(e, "reason", None), TimeoutError):
                raise AnkiError("Anki didn't answer in time") from e
            raise AnkiError("Anki isn't running (AnkiConnect can't be reached)") from e
        if reply.get("error"):
            raise AnkiError(reply["error"])
        return reply.get("result")

    def sync(self):
        """Anki's own sync with AnkiWeb, collection and media. Blocks until it's done."""
        # A full sync waits for the user to pick upload or download in Anki's window, hence the long wait.
        self.invoke("sync", timeout=SYNC_TIMEOUT)

    @property
    def ready(self) -> bool:
        """Whether a note type is chosen, so the lookup page shows + buttons."""
        return bool(self.config["anki_model"])

    def fields(self, model: str) -> list:
        return self.invoke("modelFieldNames", modelName=model)

    def existing(self, model: str, field: str, terms) -> set:
        """Which of [terms] already have a note of [model] with exactly that [field]."""
        terms = list(terms)
        if not terms:
            return set()
        query = f'"note:{_quote(model)}" (' + " OR ".join(f'"{_quote(f"{field}:{t}")}"' for t in terms) + ")"
        ids = self.invoke("findNotes", query=query)
        if not ids:
            return set()
        found = {n["fields"].get(field, {}).get("value") for n in self.invoke("notesInfo", notes=ids)}
        return {t for t in terms if t in found}

    def store_media(self, name: str, data: bytes) -> str:
        """Stores [data] under [name] (replacing a file of that name) and returns the name Anki gave it."""
        return self.invoke("storeMediaFile", filename=name, data=base64.b64encode(data).decode())

    def add_note(self, model: str, fields: dict, tags: list):
        deck = self.config["anki_deck"] or "Default"
        self.invoke("addNote", note={
            "deckName": deck, "modelName": model, "fields": fields, "tags": tags,
            "options": {"allowDuplicate": True},  # the page asks for a second tap first
        })

    def audio(self, term: str, reading: str) -> tuple[bytes, str] | None:
        """
        The word's first recording in the add-on's source order (NHK, Shinmeikai, Forvo, JPod), and its extension;
        None when it has no recording. Raises AudioUnavailable when the server can't be asked, so that isn't taken
        for a word without one.
        """
        query = {"term": term}
        if reading:
            query["reading"] = reading
        url = self.config["local_audio"].rstrip("/") + "/?" + urllib.parse.urlencode(query)
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                sources = json.loads(response.read()).get("audioSources") or []
            if not sources:
                return None
            # Forvo's file URLs hold the word itself (/forvo/<user>/<word>.opus), which urllib won't send unquoted.
            file_url = urllib.parse.quote(sources[0]["url"], safe=":/?&=%#@")
            with urllib.request.urlopen(file_url, timeout=10) as response:
                data = response.read()
        except urllib.error.HTTPError as e:
            raise AudioUnavailable(f"the Local Audio Server failed (HTTP {e.code})") from e
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            raise AudioUnavailable("the Local Audio Server isn't reachable") from e
        except (ValueError, KeyError, TypeError) as e:
            raise AudioUnavailable("the Local Audio Server's answer couldn't be read") from e
        path = urllib.parse.urlparse(sources[0]["url"]).path
        extension = path.rsplit(".", 1)[-1].lower() if "." in path.rsplit("/", 1)[-1] else "mp3"
        return data, extension


def _quote(value: str) -> str:
    """Matches the whole field exactly: Anki reads * and _ as wildcards unless escaped."""
    return "".join("\\" + c if c in '\\"*_' else c for c in value)
