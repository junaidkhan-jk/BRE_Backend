#!/usr/bin/env python3
"""Render every ```mermaid block in the BRE docs to static SVG using headless Chrome.

Output: mermaid_svgs.json  {"<docKey>-<n>": {"svg": "...", "error": null}}
The SVGs are themed for the IDE-dark design constitution; build_html.py inlines them.
"""
import html, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = {
    "review": "../BRE_Draft1_Review.md",
    "d00": "../draft2/00_Decisions_and_Canonical_Terms.md",
    "d01": "../draft2/01_PRD.md",
    "d02": "../draft2/02_Architecture.md",
    "d03": "../draft2/03_LLD.md",
    "d04": "../draft2/04_Rule_Authoring_Guide.md",
    "d05": "../draft2/05_Build_Plan.md",
    "trd": "../BRE_TRD_v1.0.md",
    "trdfn": "../BRE_TRD_BFF_FunctionBased_v1.0.md",
}

def blocks(path):
    out, cur, lang = [], None, None
    for line in open(os.path.join(HERE, path), encoding="utf-8").read().split("\n"):
        if line.startswith("```"):
            if cur is None:
                lang, cur = line[3:].strip(), []
            else:
                if lang == "mermaid":
                    out.append("\n".join(cur))
                cur, lang = None, None
            continue
        if cur is not None:
            cur.append(line)
    return out

sources = []
for key, path in DOCS.items():
    for i, code in enumerate(blocks(path), 1):
        sources.append({"id": f"bre-{key}-{i}", "code": code})

THEME = {
    "darkMode": True, "background": "#1c1f26",
    "primaryColor": "#222630", "primaryTextColor": "#dfe3ea", "primaryBorderColor": "#61afef",
    "secondaryColor": "#1c1f26", "secondaryTextColor": "#dfe3ea", "secondaryBorderColor": "#39404e",
    "tertiaryColor": "#191c23", "tertiaryTextColor": "#dfe3ea", "tertiaryBorderColor": "#39404e",
    "lineColor": "#8a93a3", "textColor": "#dfe3ea", "mainBkg": "#222630", "nodeBorder": "#61afef",
    "clusterBkg": "#191c23", "clusterBorder": "#39404e", "titleColor": "#dfe3ea",
    "edgeLabelBackground": "#1c1f26",
    "actorBkg": "#222630", "actorBorder": "#61afef", "actorTextColor": "#dfe3ea", "actorLineColor": "#4b5566",
    "signalColor": "#9aa3b2", "signalTextColor": "#dfe3ea",
    "labelBoxBkgColor": "#222630", "labelBoxBorderColor": "#39404e", "labelTextColor": "#dfe3ea",
    "loopTextColor": "#9aa3b2", "noteBkgColor": "#2a2620", "noteTextColor": "#dfe3ea", "noteBorderColor": "#e0a458",
    "activationBkgColor": "#2b303b", "activationBorderColor": "#61afef", "sequenceNumberColor": "#15171c",
    "sectionBkgColor": "#1c1f26", "altSectionBkgColor": "#191c23", "sectionBkgColor2": "#222630",
    "taskBkgColor": "#3b6ea8", "taskBorderColor": "#61afef", "taskTextColor": "#dfe3ea",
    "taskTextOutsideColor": "#dfe3ea", "taskTextLightColor": "#dfe3ea", "taskTextDarkColor": "#dfe3ea",
    "activeTaskBkgColor": "#57cc8f", "activeTaskBorderColor": "#57cc8f",
    "gridColor": "#2b303b", "todayLineColor": "#e06c75",
    "labelColor": "#dfe3ea", "altBackground": "#191c23",
    "compositeBackground": "#191c23", "compositeTitleBackground": "#222630", "compositeBorder": "#39404e",
    "stateBkg": "#222630", "stateBorder": "#61afef", "transitionColor": "#8a93a3", "transitionLabelColor": "#9aa3b2",
    "fontFamily": "Arial, Helvetica, 'Liberation Sans', sans-serif", "fontSize": "13px",
}
CONFIG = {"startOnLoad": False, "theme": "base", "themeVariables": THEME, "securityLevel": "strict",
          "flowchart": {"htmlLabels": True, "curve": "basis", "padding": 12},
          "sequence": {"mirrorActors": False, "useMaxWidth": True},
          "gantt": {"useMaxWidth": True}}

page = f"""<!doctype html><html><head><meta charset="utf-8">
<style>body{{background:#1c1f26;margin:0;font-family:Arial,Helvetica,sans-serif}}</style>
<script src="mermaid.min.js"></script></head><body>
<div id="stage"></div>
<script>
const SOURCES = {json.dumps(sources)};
mermaid.initialize({json.dumps(CONFIG)});
(async () => {{
  const out = {{}};
  for (const s of SOURCES) {{
    try {{ const r = await mermaid.render(s.id, s.code); out[s.id] = {{svg: r.svg, error: null}}; }}
    catch (e) {{ out[s.id] = {{svg: null, error: String(e && e.message || e)}}; }}
  }}
  const pre = document.createElement('pre'); pre.id = 'result';
  pre.textContent = JSON.stringify(out); document.body.appendChild(pre);
}})();
</script></body></html>"""
render_html = os.path.join(HERE, "render.html")
open(render_html, "w", encoding="utf-8").write(page)

cmd = ["google-chrome", "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
       "--allow-file-access-from-files", "--virtual-time-budget=60000", "--window-size=1400,1000",
       "--dump-dom", "file://" + render_html]
dom = subprocess.run(cmd, capture_output=True, text=True, timeout=240).stdout
m = re.search(r'<pre id="result">(.*?)</pre>', dom, re.S)
if not m:
    sys.exit("no result block in dumped DOM (render did not finish)")
result = json.loads(html.unescape(m.group(1)))
json.dump(result, open(os.path.join(HERE, "mermaid_svgs.json"), "w"), indent=0)
bad = {k: v["error"] for k, v in result.items() if v["error"]}
print(f"rendered {len(result) - len(bad)}/{len(sources)} diagrams")
for k, e in bad.items():
    print("  ERROR", k, e[:200])
