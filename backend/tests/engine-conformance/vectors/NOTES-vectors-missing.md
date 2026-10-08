# Uncertainties recorded while transcribing S1, S2, S3, S4 and M

Each entry: the vector(s) affected, what the LLD does not fix, the line consulted,
and what was chosen. Nothing here was guessed silently.

## 1. Rule IR fragment field names: `code` vs `id`

**Vectors:** S1-01, S1-02, M-01a, M-01b, M-01c

The two existing example files write the rule's identifier as `"id"`
(`L-three-valued-logic.json` L-03, `D-decimal.json` D-07). The real IR schema at
`03_LLD.md:242-258` names it `"code"` (`$defs/rule` requires
`["code","name","scope","check","onFail","onMissing","outputs"]`).

**Chosen:** `"code"`, per the instruction to prefer the real IR names. This makes
my fragments inconsistent with L-03 / D-07, which use `"id"`. One of the two
spellings should be normalised before the loader is written.

## 2. Rule IR fragment minimality vs schema `required`

**Vectors:** S1-01, S1-02, M-01a, M-01b, M-01c

`$defs/rule` (`03_LLD.md:245`) marks `name`, `onFail` and `outputs` as required.
The existing examples omit all three. These are *fragments*, not whole policies,
so the loader presumably fills IR defaults (M8, `03_LLD.md:628`) — but M8 lists
defaults for `outputs` (`{}`) and `when` only, not for `name` or `onFail`.

**Chosen:** omit `name`, `outputs` and `onFail`, matching the existing examples.
In S1-01/S1-02 `check: "true"` can never be FALSE, so `onFail` is genuinely
unreachable. In M-01a/b/c the check is unknown, so `onFail` is never consulted
either. If the loader validates fragments against the full schema, these five
vectors need `name`/`onFail`/`outputs` added.

## 3. S1-01 reason code is not fixed by the table

**Vector:** S1-01

The §13 row (`03_LLD.md:3980`) asserts only "DATA_MISSING (never NOT_APPLICABLE)"
and names no reason code. `applyMissing` (`03_LLD.md:1199`) would derive
`"DATA_MISSING_" + SNAKE(firstMissingSymbol)`; by the worked example at
`03_LLD.md:1203` (`group.villageRiskCategory` -> `DATA_MISSING_GROUP_VILLAGE_RISK_CATEGORY`)
the symbol `member.x` would give `DATA_MISSING_MEMBER_X`.

Note that the already-written L-03 expects `DATA_MISSING_X` for the same symbol
shape (`member.x` under `check: "not member.x"`), which does **not** match that
derivation. The LLD's own §13 row for L-03 (`03_LLD.md:3960`) says `DATA_MISSING_X`,
so either the row or the `SNAKE(firstMissingSymbol)` rule is loose about whether
the root (`member.`) is included.

**Chosen:** assert `status` only, no `reasonCode`, for S1-01. This keeps the
vector true under either reading. The `firstMissingSymbol` prefix question should
be resolved in the LLD, and L-03 revisited at the same time.

## 4. `sum([])` result type: Integer or Decimal

**Vector:** S2-01a

§3.9 (`03_LLD.md:~995`) gives `sum: (List<Num>) -> Num`, "`Integer` for
`List<Integer>`", and `[] -> 0`. An empty list literal `[]` has no element type,
so whether `sum([])` is the Integer `0` or the Decimal `0` is not fixed. `count`
and `distinctCount` are unambiguously `Integer`.

**Chosen:** `{"int": 0}` for all three of S2-01a/b/c. If the type checker assigns
`[]` a Decimal element type, S2-01a becomes `{"decimal": "0"}`.

## 5. Tradeline field name in S2-04

**Vector:** S2-04

The §13 row (`03_LLD.md:3976`) abbreviates the facts as `[{bal 10}, {bal 0}]` and
the expression as `sum(t.bal)` — `t` and `bal` are clearly shorthand for the row's
width, not real names.

**Chosen:** `member.tradelines` and `currentBalance`, the spellings used verbatim
in the S3-01 worked example at `03_LLD.md:3946-3947`.

## 6. S3-02 result type: Decimal 30 vs Integer 30

**Vector:** S3-02

The row (`03_LLD.md:3974`) says `30`. The facts it shares with S3-01 encode
`currentBalance` as the JSON strings `"10"` and `"20"`, which per the README's
value-encoding table is the Decimal spelling.

**Chosen:** `{"decimal": "30"}` — a sum of Decimals is a Decimal, normalised by
`stripTrailingZeros().toPlainString()` (`03_LLD.md:887`).

## 7. S4-01 / S4-02 tradeline element contents

**Vectors:** S4-01, S4-02

The rows (`03_LLD.md:3978-3979`) fix only the *counts* — 2 bureau reports holding
1 and 2 tradelines — and say nothing about the tradelines' fields.

**Chosen:** each tradeline carries a `currentBalance`, so the elements are
non-empty records; `count` ignores the contents, so this cannot affect the
assertion.

## 8. Missing-element notation inside a list literal

**Vectors:** S2-03a, S2-03b

`sum([100, 0, 50])` in the table writes the missing element as the LLD's `0`
glyph. BEL has no missing literal.

**Chosen:** a reference to an absent fact, `member.absent`, with
`facts: {"member": {}}` — the same device the already-written L-05a/L-05b use
for `["A", 0]`.
