#!/usr/bin/env python3
"""Renders the Helm chart with its default values and checks the result is valid Kubernetes YAML.

Why this exists rather than `helm template`: helm is not installed here, and D11 confines this
build to Maven Central and npm. The alternative was to ship the chart claiming it was valid — and
ADR-018 records exactly that claim being made about the CI workflows and being false: both were
invalid YAML and could never have run. A chart nobody has rendered is in the same position.

What this DOES check: that every template renders, that the result parses as YAML, that each
document has an apiVersion and a kind, and that nothing is left holding an unsubstituted
`{{ ... }}`. What it does NOT check is Helm's own semantics or the Kubernetes schema — that needs
helm and a cluster, and the gap is stated rather than papered over.
"""

import re
import sys
from pathlib import Path

import yaml

CHART = Path(__file__).resolve().parent.parent / "deploy" / "helm" / "bre-cell"


def flatten(prefix, node, out):
    """Flattens values.yaml into the dotted paths a template refers to."""
    if isinstance(node, dict):
        for key, value in node.items():
            flatten(f"{prefix}.{key}" if prefix else key, value, out)
    else:
        out[prefix] = node
    return out


def render(text, values):
    """Substitutes the Go-template subset this chart uses."""
    # {{- if .Values.x }} ... {{- end }}: keep the body when truthy, drop it when not.
    def conditional(match):
        path, body = match.group(1), match.group(2)
        return body if values.get(path) else ""

    text = re.sub(
        r"\{\{-?\s*if\s+\.Values\.([\w.]+)\s*\}\}(.*?)\{\{-?\s*end\s*\}\}",
        conditional,
        text,
        flags=re.S,
    )

    def value(match):
        path, filt = match.group(1), match.group(2)
        if path not in values:
            raise KeyError(f"template refers to .Values.{path}, which values.yaml does not define")
        rendered = values[path]
        if isinstance(rendered, bool):
            rendered = "true" if rendered else "false"
        return f'"{rendered}"' if filt and "quote" in filt else str(rendered)

    text = re.sub(r"\{\{\s*\.Values\.([\w.]+)\s*(\|[^}]*)?\}\}", value, text)
    # Strip the comment blocks, which are Go-template comments rather than YAML ones.
    text = re.sub(r"\{\{-?\s*/\*.*?\*/\s*-?\}\}", "", text, flags=re.S)
    return text


def main():
    values = flatten("", yaml.safe_load((CHART / "values.yaml").read_text()), {})
    templates = sorted((CHART / "templates").glob("*.yaml"))
    if not templates:
        print("no templates found — nothing was checked, which is not a pass", file=sys.stderr)
        return 1

    total = 0
    for template in templates:
        try:
            rendered = render(template.read_text(), values)
        except KeyError as missing:
            print(f"  FAIL {template.name}: {missing}", file=sys.stderr)
            return 1

        leftover = re.search(r"\{\{[^}]*\}\}", rendered)
        if leftover:
            print(
                f"  FAIL {template.name}: unsubstituted {leftover.group(0)}", file=sys.stderr
            )
            return 1

        try:
            documents = [d for d in yaml.safe_load_all(rendered) if d]
        except yaml.YAMLError as broken:
            print(f"  FAIL {template.name}: not valid YAML once rendered: {broken}",
                  file=sys.stderr)
            return 1

        for document in documents:
            for required in ("apiVersion", "kind"):
                if required not in document:
                    print(f"  FAIL {template.name}: a document has no {required}", file=sys.stderr)
                    return 1
            if "metadata" not in document or "name" not in document.get("metadata", {}):
                print(f"  FAIL {template.name}: a {document['kind']} has no metadata.name",
                      file=sys.stderr)
                return 1

        total += len(documents)
        print(f"  ok   {template.name}: {len(documents)} resources "
              f"({', '.join(d['kind'] for d in documents)})")

    print(f"\n  {total} resources render and parse. NOT checked: Helm's own semantics and the")
    print("  Kubernetes schema — both need helm and a cluster.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
