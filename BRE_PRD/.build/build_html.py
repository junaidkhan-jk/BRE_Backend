#!/usr/bin/env python3
"""Build docs/BRE_PRD/html/ from the BRE markdown sources using the html-creator template.

Usage:  python3 render_mermaid.py   # only when diagrams change (needs google-chrome + mermaid.min.js)
        python3 build_html.py
"""
import html, json, os, re, sys
from markdown_it import MarkdownIt
from content_meta import DOCS, DATE

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "html"))
TEMPLATE = os.path.expanduser("~/.claude/skills/html-creator/assets/template.html")
SVGS = json.load(open(os.path.join(HERE, "mermaid_svgs.json")))

EXTRA_CSS = """
/* ---- content-specific additions (BRE build) ---- */
.fig-kick{font-family:var(--mono);font-size:10.5px;text-transform:uppercase;letter-spacing:.11em;color:var(--faint);margin:0 0 10px}
figure.diagram svg{max-height:none}
.docnav{display:flex;justify-content:space-between;gap:16px;margin-top:3em;padding-top:1.2em;border-top:1px solid var(--line);font-size:14px}
.docnav a{border-bottom:none}
.doccards{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:14px;margin:1.3em 0}
.doccard{background:var(--card);border:1px solid var(--line);border-radius:11px;padding:16px 18px;box-shadow:0 1px 2px rgba(0,0,0,.35)}
.doccard h3{margin:.1em 0 .35em;font-size:1.12rem}
.doccard h3 a{border-bottom:none}
.doccard p{margin:.35em 0;font-size:14px;color:var(--muted)}
.doccard .meta{font-family:var(--mono);font-size:11px;color:var(--faint);margin-top:.6em}
.fig-head{display:flex;align-items:center;justify-content:space-between;gap:12px}
.fig-zoom{font-family:var(--mono);font-size:10.5px;letter-spacing:.06em;color:var(--muted);background:var(--card-2);
  border:1px solid var(--line-2);border-radius:999px;padding:3px 10px;cursor:pointer;white-space:nowrap;margin:0 0 10px}
.fig-zoom:hover{color:var(--accent)}
figure.diagram.zoomed .fig-scroll{overflow-x:auto}
figure.diagram.zoomed .fig-scroll svg{width:var(--natw) !important;max-width:none !important}
@media print{.fig-zoom{display:none}}
"""
ZOOM_JS = """<script>
/* Wide diagrams: toggle between fit-to-width and natural size (scrollable). Vanilla, file:// safe. */
[].slice.call(document.querySelectorAll('.fig-zoom')).forEach(function(b){
  b.addEventListener('click',function(){var f=b.closest('figure');var z=f.classList.toggle('zoomed');
    b.textContent=z?'fit to width':'full size \u2194';});
});
</script>"""

# Mermaid bakes colours into each SVG's <style>. Map the theme colours back to the page tokens,
# so a printed or PDF'd copy re-colours with the template's light print palette.
COLOR_VARS = {
    "#222630": "var(--card-2)", "#1c1f26": "var(--card)", "#191c23": "var(--stripe)",
    "#dfe3ea": "var(--ink)", "#61afef": "var(--accent)", "#8a93a3": "var(--faint)",
    "#9aa3b2": "var(--muted)", "#39404e": "var(--line-2)", "#2b303b": "var(--line)",
    "#4b5566": "var(--quote)", "#e0a458": "var(--amber)", "#3b6ea8": "var(--accent-soft)",
    "#e06c75": "var(--crimson)", "#57cc8f": "var(--green)", "#15171c": "var(--canvas)",
    "#2a2620": "var(--amber-soft)",
}

def themable(svg):
    def fix(block):
        for hexv, var in COLOR_VARS.items():
            block = re.sub(re.escape(hexv), var, block, flags=re.I)
        return block
    svg = re.sub(r"<style>.*?</style>", lambda m: fix(m.group(0)), svg, flags=re.S)
    svg = re.sub(r'style="([^"]*)"', lambda m: 'style="' + fix(m.group(1)) + '"', svg)
    return svg

def plain(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s)).strip()

def slug(text, used):
    base = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:70] or "section"
    s, n = base, 2
    while s in used:
        s, n = f"{base}-{n}", n + 1
    used.add(s)
    return s

def link_fix(body):
    names = {os.path.basename(d["src"]): d["out"] for d in DOCS}
    def rep(m):
        href = m.group(1)
        path, _, frag = href.partition("#")
        base = os.path.basename(path)
        if base in names:
            return f'href="{names[base]}' + (f"#{frag}" if frag else "") + '"'
        if base == "README.md":
            return 'href="index.html"'
        return m.group(0)
    return re.sub(r'href="([^"]+\.md(?:#[^"]*)?)"', rep, body)

md = MarkdownIt("commonmark", {"html": True}).enable("table").enable("strikethrough")
tpl = open(TEMPLATE, encoding="utf-8").read()
tpl = tpl.replace("</style>", EXTRA_CSS + "</style>", 1)

def fill(title, breadcrumb, intro, content, footer):
    page = tpl.replace("{{TITLE}}", html.escape(title))
    page = page.replace("{{BACK_HREF}}", "index.html").replace("{{BACK_LABEL}}", "BRE Draft 2 index")
    page = page.replace("{{BREADCRUMB}}", html.escape(breadcrumb))
    page = re.sub(r"\{\{INTRO[^}]*\}\}", lambda m: intro, page)
    page = re.sub(r"\{\{FOOTER[^}]*\}\}", lambda m: footer, page)
    page = page.replace("{{CONTENT}}", content)
    if 'class="fig-zoom"' in page:
        page = page.replace("</body>", ZOOM_JS + "\n</body>", 1)
    return page

warnings = []
for di, d in enumerate(DOCS):
    src = os.path.normpath(os.path.join(HERE, d["src"]))
    body = md.render(open(src, encoding="utf-8").read())

    m = re.search(r"<h1>(.*?)</h1>", body, re.S)
    title = plain(m.group(1)) if m else d["short"]
    if m:
        body = body[:m.start()] + body[m.end():]

    used = {"doc-title"}
    def h2(mm):
        inner = mm.group(1); text = plain(inner)
        out = ""
        for prefix, kick in d["kickers"].items():
            if text.startswith(prefix):
                out += f'<span class="kicker">{html.escape(kick)}</span>\n'
        meaning = d["meanings"].get(text)
        if meaning is None:
            warnings.append(f"{d['key']}: no meaning for h2 '{text}'")
            meaning = ""
        out += f'<h2 id="{slug(text, used)}">{inner}</h2>\n'
        if meaning:
            out += f'<p class="meaning"><b>What this means:</b> {html.escape(meaning)}</p>\n'
        return out
    body = re.sub(r"<h2>(.*?)</h2>", h2, body, flags=re.S)
    body = re.sub(r"<h3>(.*?)</h3>", lambda mm: f'<h3 id="{slug(plain(mm.group(1)), used)}">{mm.group(1)}</h3>', body, flags=re.S)

    # Diagrams: replace each mermaid code block, in order, with its pre-rendered SVG.
    counter = {"n": 0}
    heads = []
    def fig(mm):
        counter["n"] += 1; n = counter["n"]
        key = f"bre-{d['key']}-{n}"
        rec = SVGS.get(key) or {}
        before = body_ref[:mm.start()]
        hs = re.findall(r"<h[23][^>]*>(.*?)</h[23]>", before, re.S)
        where = plain(hs[-1]).rstrip("#").strip() if hs else ""
        code = html.unescape(mm.group(1))
        kind = code.strip().split()[0] if code.strip() else "diagram"
        kind = {"sequenceDiagram": "sequence", "flowchart": "flow", "stateDiagram-v2": "state machine",
                "gantt": "timeline", "graph": "flow"}.get(kind, kind)
        cap = d["captions"].get(n)
        if not cap:
            warnings.append(f"{d['key']}: no caption for diagram {n}")
            cap = "<b>Reading it:</b> see the surrounding section."
        if not rec.get("svg"):
            warnings.append(f"{d['key']}: diagram {n} not rendered ({rec.get('error')})")
            inner = f"<pre><code>{html.escape(code)}</code></pre>"
        else:
            inner = themable(rec["svg"]).replace("<svg ", f'<svg role="img" aria-label="{html.escape(where)} ({kind} diagram)" ', 1)
        mw = re.search(r"max-width:\s*([\d.]+)px", (rec.get("svg") or "")[:600])
        natw = float(mw.group(1)) if mw else 0
        zoom = ""
        if natw > 1100:
            inner = f'<div class="fig-scroll" style="--natw:{int(natw)}px">{inner}</div>'
            zoom = '<button class="fig-zoom" type="button" title="Show this diagram at natural size">full size \u2194</button>'
        return (f'<figure class="diagram"><div class="fig-head"><div class="fig-kick">Diagram {n} · {html.escape(kind)} · {html.escape(where)}</div>{zoom}</div>'
                f"{inner}<figcaption>{cap}</figcaption></figure>")
    body_ref = body
    body = re.sub(r'<pre><code class="language-mermaid">(.*?)</code></pre>', fig, body, flags=re.S)
    if counter["n"] != len(d["captions"]) and d["captions"]:
        warnings.append(f"{d['key']}: {counter['n']} diagrams but {len(d['captions'])} captions")

    body = link_fix(body)

    prev_d = DOCS[di - 1] if di > 0 else None
    next_d = DOCS[di + 1] if di + 1 < len(DOCS) else None
    nav = '<nav class="docnav">'
    nav += f'<a href="{prev_d["out"]}">← {html.escape(prev_d["short"])}</a>' if prev_d else '<a href="index.html">← Index</a>'
    nav += f'<a href="{next_d["out"]}">{html.escape(next_d["short"])} →</a>' if next_d else '<a href="index.html">Index →</a>'
    nav += "</nav>"

    rel = os.path.relpath(src, os.path.join(HERE, ".."))
    page = fill(title, f"docs/BRE_PRD/{rel}", html.escape(d["intro"]), body + nav,
                f"Rendered from <code>docs/BRE_PRD/{rel}</code> · generated with the html-creator template (Trustt Design Constitution, IDE Dark) · {DATE}. "
                "The markdown file is the source of truth; regenerate with <code>docs/BRE_PRD/.build/build_html.py</code>.")
    os.makedirs(OUT, exist_ok=True)
    open(os.path.join(OUT, d["out"]), "w", encoding="utf-8").write(page)
    print(f"wrote {d['out']}  ({counter['n']} diagrams, {len(page)//1024} KB)")

# ---------------- index page ----------------
def count(pattern, path):
    return len(re.findall(pattern, open(os.path.join(HERE, path), encoding="utf-8").read(), re.M))

fr = count(r"^\| FR-[A-Z]+-\d{3} \|", "../draft2/01_PRD.md")
adr = count(r"^### ADR-\d{3}", "../draft2/02_Architecture.md")
lines = sum(len(open(os.path.join(HERE, x["src"]), encoding="utf-8").read().split("\n")) for x in DOCS if x["key"] not in ("review", "trd", "trdfn"))
diagrams = sum(len(x["captions"]) for x in DOCS)
examples = count(r"^### E\d+\.", "../draft2/04_Rule_Authoring_Guide.md")

cards = []
blurbs = {
    "d00": ("Read first", "steel", "The 15 decisions and the shared vocabulary. If documents disagree, this one wins."),
    "d01": ("Product", "steel", "Who it is for, the 147 requirements with acceptance tests, the regulatory scope and the release plan. Replaces the BRD."),
    "d02": ("Engineering", "steel", "Capacity arithmetic, latency budget, cells, security, disaster recovery and 15 ADRs. Replaces the HLD and System Design."),
    "d03": ("Engineering", "steel", "BEL grammar and semantics, the policy JSON Schema, DDL, APIs, events, algorithms and conformance vectors. Replaces the LLD."),
    "d04": ("Policy analysts", "green", "Plain-language concepts, the rule language, and 21 worked policies with checked test tables."),
    "d05": ("Delivery", "amber", "How we build it from scratch: milestones with tasks and exits, critical path, team and effort, test strategy and the decisions needed to start."),
    "trd": ("Engineering", "green", "The standalone TRD: every technical decision, contract, algorithm, data structure and target in one self-contained document."),
    "trdfn": ("Engineering · variant", "green", "The TRD variant with the Backend-for-Frontend and function-based policy authoring as an option: a function library, a platform function pack and a rule form builder."),
    "review": ("Background", "purple", "Why Draft 1 had to change: the blocking defects and the gaps, document by document."),
}
order = ["trd", "trdfn", "d00", "d01", "d02", "d03", "d04", "d05", "review"]
bykey = {x["key"]: x for x in DOCS}
for k in order:
    x = bykey[k]
    tag, rail, blurb = blurbs[k]
    n = len(open(os.path.join(HERE, x["src"]), encoding="utf-8").read().split("\n"))
    cards.append(f'<div class="doccard"><span class="pill {rail}">{tag}</span><h3><a href="{x["out"]}">{html.escape(x["short"])}</a></h3>'
                 f'<p>{html.escape(blurb)}</p><div class="meta">{os.path.basename(x["src"])} · {n:,} lines</div></div>')

index_body = f"""
<span class="kicker">AT A GLANCE</span>
<h2 id="what-is-here">What is in this folder</h2>
<p class="meaning"><b>What this means:</b> the complete Draft 2 specification of the BRE Platform as browsable pages, plus the review of Draft 1 that explains why it changed.</p>
<div class="stats">
  <div class="stat"><div class="k">Requirements</div><div class="v">{fr}</div><div class="s">each with priority, phase and test</div></div>
  <div class="stat"><div class="k">Design decisions</div><div class="v">{adr}</div><div class="s">ADRs with rejected alternatives</div></div>
  <div class="stat"><div class="k">Worked policies</div><div class="v">{examples}</div><div class="s">with hand-checked test tables</div></div>
  <div class="stat"><div class="k">Diagrams</div><div class="v">{diagrams}</div><div class="s">rendered inline, no network</div></div>
  <div class="stat"><div class="k">Draft 2 size</div><div class="v">{lines:,}</div><div class="s">lines of source markdown</div></div>
</div>
<div class="doccards">{''.join(cards)}</div>

<span class="kicker">HOW TO READ</span>
<h2 id="reading-order">Where should I start?</h2>
<p class="meaning"><b>What this means:</b> start with the decisions file, then read the one document written for your role.</p>
<table>
<thead><tr><th>If you are…</th><th>Read</th></tr></thead>
<tbody>
<tr><td>Deciding whether to approve the direction</td><td><a href="BRE_Draft1_Review.html">Draft 1 Review</a> §0, then <a href="00_Decisions_and_Canonical_Terms.html">00</a> §1, then <a href="01_PRD.html">01 PRD</a> §1–3 and §12</td></tr>
<tr><td>Product, credit policy, risk or compliance</td><td><a href="01_PRD.html">01 PRD</a>, especially §5, §8 and §10</td></tr>
<tr><td>Architect, SRE or security</td><td><a href="02_Architecture.html">02 Architecture</a>, especially §6 Scalability, §9 Security and §10 Resilience</td></tr>
<tr><td>Engineer who wants one document</td><td><a href="BRE_TRD_v1.0.html">BRE TRD v1.0</a> (standalone)</td></tr>
<tr><td>Weighing function-based authoring</td><td><a href="BRE_TRD_BFF_FunctionBased_v1.0.html">BRE TRD · BFF + Function option</a>, §14</td></tr>
<tr><td>Engineer building the platform</td><td><a href="03_LLD.html">03 LLD</a>, starting with §3 BEL and §13 conformance vectors</td></tr>
<tr><td>Policy analyst writing rules</td><td><a href="04_Rule_Authoring_Guide.html">04 Authoring Guide</a> §1–3, then the cookbook</td></tr>
<tr><td>Planning or staffing the build</td><td><a href="05_Build_Plan.html">05 Build Plan</a>, especially §1, §5, §7 and §11</td></tr>
</tbody>
</table>

<span class="kicker">STATUS</span>
<h2 id="status">How finished is it?</h2>
<p class="meaning"><b>What this means:</b> the specification is complete and internally consistent, but it is a draft for review and nothing has been built or run yet.</p>
<table>
<thead><tr><th>Item</th><th>State</th></tr></thead>
<tbody>
<tr><td>All Draft 2 documents written and cross-checked; conflicts resolved in 00</td><td><span class="pill green">done</span></td></tr>
<tr><td>Document status</td><td><span class="pill amber">draft for review</span> — owners and sign-off are placeholders</td></tr>
<tr><td>YAML and JSON examples parse; the example policy validates against its JSON Schema</td><td><span class="pill green">checked</span></td></tr>
<tr><td>LLD SQL</td><td><span class="pill amber">parsed, never executed</span> — no PostgreSQL was available</td></tr>
<tr><td>Diagrams</td><td><span class="pill green">rendered</span> — static SVG, embedded in each page</td></tr>
<tr><td>Platform code</td><td><span class="pill crimson">not started</span> — the plan is <a href="05_Build_Plan.html">05 Build Plan</a>; first is M0 Foundations, then M1, <code>bre-engine-core</code> against the LLD conformance vectors</td></tr>
<tr><td>Regulatory figures</td><td><span class="pill amber">to confirm</span> — held as parameters; compliance must confirm current values</td></tr>
</tbody>
</table>
"""
page = fill("BRE Platform — Draft 2 Specification", "docs/BRE_PRD/html/index.html",
            "The browsable edition of the BRE Platform Draft 2 specification: a fresh, independent, multi-tenant business-rules and decisioning product for Indian lenders. Each page is self-contained, works offline and opens straight from disk. The markdown files in <code>docs/BRE_PRD/draft2/</code> remain the source of truth. Generated 2026-10-01.",
            index_body,
            f"Generated with the html-creator template (Trustt Design Constitution, IDE Dark) · {DATE} · regenerate with <code>docs/BRE_PRD/.build/build_html.py</code>.")
page = page.replace('<a class="back" href="index.html">← BRE Draft 2 index</a>', '<a class="back" href="#doc-title">BRE Draft 2</a>')
open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(page)
print("wrote index.html")

for w in warnings:
    print("WARN", w)
sys.exit(1 if warnings else 0)
