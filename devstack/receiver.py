#!/usr/bin/env python3
"""A downstream system, for demonstrating decision delivery.

Stands in for a lender's origination system: it accepts CloudEvents on POST /decisions, appends
each one to a file, and answers with whatever status the control file says. That last part is the
point — a receiver that only ever says 200 proves the happy path and nothing about what happens
when your system is down, which is the half that matters.

  devstack/var/receiver.status   write 503 to make it fail, 200 to recover, 400 to reject
  devstack/var/receiver.log      one line per event received, in order
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

VAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "var")
LOG = os.path.join(VAR, "receiver.log")
STATUS = os.path.join(VAR, "receiver.status")


def answer() -> int:
    """The status to answer with. Read per request, so it can be changed without a restart."""
    try:
        with open(STATUS) as handle:
            return int(handle.read().strip() or "200")
    except (OSError, ValueError):
        return 200


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler's naming
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        status = answer()

        try:
            event = json.loads(raw)
            decision = (event.get("data") or {}).get("decision", "?")
            line = (
                f'{status} id={event.get("id", "?")} type={event.get("type", "?")} '
                f'key={event.get("recordkey", "?")} decision={decision} '
                f'ce-id-header={self.headers.get("ce-id", "?")} '
                f'auth={"yes" if self.headers.get("Authorization") else "no"}'
            )
        except json.JSONDecodeError:
            line = f"{status} UNPARSEABLE {raw[:120]}"

        # Logged even when refusing, so a retry is visible as a second line with the same id —
        # which is what at-least-once looks like from the receiving end.
        with open(LOG, "a") as handle:
            handle.write(line + "\n")

        self.send_response(status)
        self.end_headers()

    def log_message(self, fmt: str, *args: object) -> None:
        """Silenced: the event log above is the record, and stderr noise buries it."""


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    os.makedirs(VAR, exist_ok=True)
    open(LOG, "w").close()
    with open(STATUS, "w") as handle:
        handle.write("200")
    print(f"receiver listening on {port}, logging to {LOG}", flush=True)
    HTTPServer(("127.0.0.1", port), Receiver).serve_forever()
