# Conformance vectors

Language-neutral test vectors for the BRE engine, transcribed from **LLD §13**
(`BRE_PRD/draft2/03_LLD.md:3934-4026`). Every engine build must pass all of them,
and so must the studio's TypeScript preview evaluator if one is ever added.

These files are the artifact of record. The JUnit runner in
`../src/test/java/dev/bre/conformance/` only reads them.

## File format

A file holds either one vector object or an array of them, so related vectors can
be grouped. Fields, per the shape fixed at `03_LLD.md:3938-3952`:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Vector id from the LLD §13 table, e.g. `S3-01`. Unique across all files. |
| `description` | yes | What the vector pins down, in the LLD's own words where possible. |
| `asOf` | yes | The evaluation date. Evaluation never reads a clock (D8); it uses only this. |
| `scope` | no | Entity scope, e.g. `MEMBER`, `GROUP`. Omit for scope-free expressions. |
| `params` | no | Policy parameters, bound under the `param.` namespace. Defaults to `{}`. |
| `lists` | no | Named lists, bound under the `list.` namespace. Defaults to `{}`. |
| `facts` | no | The fact snapshot. Defaults to `{}`. |
| `expr` | one of | A BEL expression. Expression vectors expect a `value`. |
| `policy` | one of | An IR fragment. Policy vectors expect a `status`, `decision` or `steps`. |
| `expect` | yes | The expected outcome. |

A vector must carry **exactly one** of `expr` or `policy`; the loader rejects both
and neither.

### Where the LLD table row asserts several things

One row often fixes more than one fact — `D-06` gives four rounding cases. Each
assertion becomes its own vector, suffixed `a`, `b`, `c`…, so a failure names one
fact rather than a group. The family prefix is unchanged, so `D-06a` still belongs
to family `D`.

## Encoding values

### Missing, and unknown

`03_LLD.md:845` — "`U` means unknown, which is a missing Boolean." Unknown and
missing are the **same value**, so the LLD's `U` and `∅` both encode as:

```json
"expect": { "value": { "missing": true } }
```

A missing *fact* is simply an absent field in `facts`. There is no explicit null.

### Present values

`expect.value` carries exactly one typed key:

| Key | For | Example |
|---|---|---|
| `missing` | missing / unknown | `{ "missing": true }` |
| `bool` | Boolean | `{ "bool": false }` |
| `decimal` | Decimal | `{ "decimal": "0.6666666667" }` |
| `int` | Integer | `{ "int": 21 }` |
| `text` | Text | `{ "text": "APPROVE" }` |
| `date` | Date | `{ "date": "2026-02-28" }` |
| `list` | List | `{ "list": [ { "int": 1 } ] }` |

**Decimals are JSON strings in plain notation.** `03_LLD.md:887` fixes result
serialisation as `stripTrailingZeros().toPlainString()`, as a JSON string, and
rejects exponent form. Writing `0.5000035714` as a JSON number would hand the
value to a double somewhere in the chain and quietly lose the property the vector
exists to check.

Comparisons ignore scale (`compareTo`), so `1.0 = 1.00` is TRUE — but a vector
asserting a *value* states the normalised form.

### Errors

A runtime error is reported on the result, not as an HTTP error
(`03_LLD.md:1026`). Expected form:

```json
"expect": { "error": "EVAL_DIV_ZERO" }
```

Policy vectors that expect an error alongside a decision carry both keys.

### Policy-level outcomes

```json
"expect": { "status": "DATA_MISSING", "reasonCode": "DATA_MISSING_X" }
"expect": { "decision": "REFER" }
"expect": { "steps": [ { "ref": "…", "status": "NOT_APPLICABLE" } ] }
```

### Use the IR's own field names

Where a vector names something the IR also names, the vector uses the IR spelling —
anything else creates two words for one thing and a bug at the loader boundary:

| Thing | Field | Fixed at |
|---|---|---|
| A rule's identifier, inside `policy.rules[]` | `code`, not `id` | `03_LLD.md:242-258` |
| A flow step's identity, in `expect.steps[]` and `policy.flow…steps[]` | `ref`, not `id` | `03_LLD.md:446`, `03_LLD.md:2783` |
| A component's named output | `expect.outputs` | `03_LLD.md:437` |
| Per-entity results | `entityType`, `entityRef`, `status`, `effectiveStatus`, `reasonCode`, `deviationType`, `measure`, `worseWhen`, `requiredLevel`, `annotation`, `outputs` | `03_LLD.md:2804-2811`, `03_LLD.md:2913-2916` |

The vector's own top-level `id` is unrelated to either and stays `id`.

### `params` carry typed values

`params` use the same typed envelope as `expect.value`, because a bare JSON `20` does not
distinguish an Integer from a Decimal and §13's S7-06 calls its parameter an Integer
whole-number percent:

```json
"params": { "GROUP_DEVIATION_SHARE_REFER_PCT": { "int": 20 } }
```

### Explicit `null` — the one exception

A missing *fact* is an absent field, never `null`. The exception is an authority-matrix
bound, where `03_LLD.md:1322` gives `null` a meaning of its own ("a null bound means
unbounded"). That is a present value that happens to be unbounded, not a missing one.

## Status

**All 90 vectors pass** (2026-10-08). Both shapes run against the real engine: an expression
vector through the parser and evaluator, a policy vector through the IR codec and the full flow.

The runner compares only the keys a vector asserts, because the §13 rows assert different things
from one another — some a `status`, some a `decision`, some a list of `steps`. A vector that
asserted nothing would therefore pass vacuously, which is why the suite requires every `expect` to
be non-empty. Steps and entities are matched by identity (`ref`, `entityRef`) rather than by
position, so a row naming one step asserts what *that* step looked like, wherever it sits in the
flow.

Deviations from the §13 table, each recorded where it was made:

- **L-03's reason code** was corrected from `DATA_MISSING_X` to `DATA_MISSING_MEMBER_X`. Two §13
  rows contradict each other; this one follows §4.6's derivation rule and its worked example, which
  S5-01 also asserts. See the vector's own `description` and `docs/OPEN-QUESTIONS.md` Q2.
- **S12-01's third assertion** is not transcribed: BEL-016 makes a reference to a skipped step
  unwritable, so no expression can observe it. Q3.
