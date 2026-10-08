# HTML build for docs/BRE_PRD/html

This folder regenerates the HTML edition from the markdown sources. The markdown is the source of truth.

**Rebuild the pages:**

    python3 build_html.py

**Re-render the diagrams.** This is needed only when a `mermaid` block changes. It needs `google-chrome` and the bundled `mermaid.min.js`:

    python3 render_mermaid.py && python3 build_html.py

| File | Purpose |
|---|---|
| `content_meta.py` | The per-document additions the Design Constitution requires: intros, "What this means" captions, PART kickers and diagram captions. A new `##` section needs a new entry here; the build warns if one is missing. |
| `mermaid_svgs.json` | The pre-rendered diagrams, themed IDE dark. |
| `mermaid.min.js` | mermaid v11, used only at build time. It is never shipped in the HTML. |
