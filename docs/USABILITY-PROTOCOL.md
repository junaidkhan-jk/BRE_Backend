# Studio usability sessions — the protocol

**What this is for:** M5-10 and PRD risk **RK1** — *"BEL is too technical for business analysts, so
engineering stays in the loop and K2 is missed."* M5-10's exit is "RK1 assessed; fixes scheduled",
which needs three or more credit-policy analysts. They are not something code supplies. This is the
protocol for the hour you get with each of them, and it is written to make that hour produce a
decision rather than a feeling.

**K2 is the number RK1 threatens:** *% of PROD policy deployments with no engineering ticket*,
target ≥ 70% at Phase 1 and ≥ 90% at Phase 2, measured from ~0% today. Every task below is shaped to
produce evidence about that one percentage.

---

> **The studio is a separate repository.** Paths below written `BRE_UI: src/…` are in
> [BRE_UI](https://github.com/junaidkhan-jk/BRE_UI), not here, so the CI check that verifies this
> document's citations cannot confirm them — it checks only the paths in this repository. Verify the
> three RK1 rows against the UI repository by hand before a session.

## Read this before scheduling anything

RK1's own entry in the risk register names six mitigations. **Three of them do not exist.** A
session run today therefore measures a studio missing half of its intended defences, and it will
"discover" exactly what the register already predicts. That is not an argument against running the
sessions — it is an argument for asking a different question, and the protocol below asks it.

| RK1 mitigation | Built? | Where |
|---|---|---|
| An **autocomplete-first editor** | **YES** | `BRE_UI: src/components/BelEditor.tsx` — Monaco with a real completion provider, hover types, and live diagnostics from the actual type checker rather than a regex |
| **Decision tables for most logic** | **PARTIAL — and this is the crux** | `BRE_UI: src/screens/PolicyStructure.tsx` renders the grid with gap/overlap witnesses and row hit counts, **read-only and deliberately so**. Authoring a table means editing PSF text — and a table's `when` cells are BEL unary tests (§3.3), so the analyst is writing BEL *plus* YAML indentation. The mitigation as written ("tables instead of BEL") is not what the product currently offers |
| The **Authoring Guide with a cookbook** | **YES** | `BRE_PRD/draft2/04_Rule_Authoring_Guide.md` — §4 is 21 worked examples. It is a specification document, not in-product help; whether an analyst finds and uses it is one of the things to observe |
| **Policy-sentence view** | **NO** | not built. The phrase appears only in source comments |
| **Starter packs** | **NO** | not built |
| **Usability tests with real analysts** | this document | — |

**So the sessions' real question is not "is the studio usable".** It is: *of the three missing
mitigations, which would have changed the outcome of the tasks we watched fail?* That question has a
build decision attached to it. "Is it usable" does not.

---

## Who to recruit, and who not to

**Three or more credit-policy analysts** who currently write or own credit policy at a lender — the
people whose work K2 is about. Specifically:

- they must have changed a real credit rule in the last year, by whatever means they use today
- they must **not** have seen this studio before, and must not be from the project team
- at least one should be someone who today writes the rule *in prose* and hands it to engineering,
  because that is the person RK1 is about

Who will give you a false pass: anybody who has written code, anybody who has sat through a demo,
and anybody who reports to whoever commissioned the session. A product owner completing the tasks
easily tells you nothing, and it is the most common way a usability test gets a clean result.

Three is a floor, not a target. Three analysts find most of what is badly wrong; they do not
establish a rate, and the report must not state one.

---

## The environment

```bash
./devstack/up.sh --sandbox        # registry, PostgreSQL, one account per role
cd ../BRE_UI && VITE_USE_FIXTURES=false \
    VITE_REGISTRY_URL=http://127.0.0.1:8081 npm run dev
```

`--sandbox` seeds the `sandbox` tenant with one account per role — author, reviewer, approver,
viewer, auditor — so maker-checker can be walked by one facilitator switching accounts. The password
is printed to the registry log. **Never enable `--sandbox` anywhere real.**

**Verify the environment before the analyst arrives, every time.** The devstack has silently served
a *previous run's* policy — the registry reported the new version ACTIVE while the pod kept an older
manifest, because manifest versions restart at 1 on a fresh database and §8.1 refuses a version that
is not newer. `./devstack/up.sh` now clears the stale manifest and `e2e.sh` step 13 decides all four
reference fixtures and compares, so the walk fails loudly rather than quietly. Confirm it got to
step 13 with four green rows. An analyst's hour spent on a stale policy is the hour wasted.

---

## The tasks

Realistic, in the order a real change happens, and each one is a thing an analyst does today by
emailing engineering. **Give the analyst the business intent, never the mechanism** — the point is
whether they can find the mechanism.

### Task 1 — Find out what is live (5 min)

> *"A branch manager says a five-member group was declined last Tuesday and wants to know why.
> Find out what policy was in force, and what the rule that declined them says."*

Watch for: do they go to the deployment console or the policy list first? Do they understand that
the *version* and the *bundle* are different things? Can they read a rule without asking what `<=`
means in this context?

**Fails if:** they cannot establish which version was live without help.

### Task 2 — Read a decision (10 min)

> *"Here is that application's decision id. Explain to the branch manager, in your own words, why it
> was declined."*

Watch for: whether the trace reads as reasons or as a stack of codes. Whether they notice masking and
whether it bothers them. Whether they look for the *reason code text* and find it.

**Fails if:** their explanation is wrong, or they cannot produce one without the facilitator reading
the screen to them.

### Task 3 — Change a threshold (15 min) — **the K2 task**

> *"The credit committee has approved raising the minimum bureau score from 600 to 640. Make that
> change, ready for review."*

This is the single most common real change and the one K2 is measured on. It is a parameter, not a
rule — so the interesting failure is an analyst who edits the *rule* to say `>= 640` instead of
changing `MIN_BUREAU_SCORE`. Both compile. One is a policy change; the other hard-codes a threshold
that was deliberately made a parameter, and it will be wrong the next time the committee moves it.

Watch for: parameter vs rule. Whether they write a change note without being prompted. Whether the
static checks' output means anything to them. Whether they hit YAML indentation.

**Fails if:** they hard-code the threshold, or they cannot get to a submitted state unaided.

### Task 4 — Add a rule (20 min) — **the RK1 task**

> *"The committee wants to decline any group whose village risk category is D. Add that."*

This is where RK1 either materialises or does not. There is no way to do it without writing a rule,
and the mitigation that was supposed to carry this — decision tables instead of BEL — is read-only
today, so the analyst is in PSF with a BEL expression and a YAML block.

Watch for, in this order: do they find where rules live at all; do they copy an existing rule (the
cookbook behaviour the guide intends, and the thing a starter pack would make trivial); does
autocomplete help or get in the way; what do they do with their first diagnostic; **do they ask for
engineering help, and at what minute.**

**Fails if:** they ask for help, or the rule they write does not do what was asked.

### Task 5 — Test before submitting (10 min)

> *"Prove to yourself that your change does what you think, before anybody reviews it."*

Watch for: do they reach for the simulator, the test cases, or neither? Do they trust the compile as
proof? An analyst who submits an untested change has not done anything wrong by the product's rules,
and that is itself a finding.

**Fails if:** they have no way to satisfy themselves, or they believe "it compiled" means "it works".

### Task 6 — Review somebody else's change (10 min, as the reviewer account)

> *"A colleague has submitted this. Decide whether to approve it."*

Watch for: does the diff show them what changed in business terms or in text? Do they check the
tests? Do they notice they cannot approve their own work, and does that read as a bug or as a
control?

**Fails if:** they approve without being able to say what changed.

---

## What is measured

Per task, per analyst. Four values, and the first one is the one that matters:

| | |
|---|---|
| **Unaided / Hinted / Assisted / Abandoned** | the only measure tied directly to K2. **Assisted** means the facilitator told them something the product should have. Count assists; they are the backlog |
| Time to first correct action | distinguishes "could not find it" from "could not do it" — different fixes |
| Errors recovered without help | a product that lets you dig out is usable at a different level from one that does not |
| Verbatim quotes at the moment of difficulty | the only thing that survives into a design discussion intact. Write them down as said, including the swearing |

**Do not collect a satisfaction score.** Three analysts cannot produce one that means anything, and a
number invites the number to be managed. What the report needs is the assist list.

### The pass line, stated before the sessions run

Set it now, in writing, so it cannot be adjusted afterwards:

- **Tasks 1, 2, 3, 6 unaided by at least 2 of 3 analysts.** These are the everyday path. If an
  analyst needs engineering to raise a bureau-score threshold, K2's 70% is not reachable and the
  finding is unambiguous.
- **Task 4 unaided by at least 1 of 3.** Adding a rule is the hard case and the one RK1 names; one
  of three succeeding unaided is the threshold at which the missing mitigations are an improvement
  rather than a prerequisite. **Zero of three means RK1 has materialised** and the three unbuilt
  mitigations stop being optional.
- **No analyst abandons Task 3.** An abandoned threshold change is a product that cannot meet K1
  either, because a change nobody can make has no lead time.

---

## Running the session

**One facilitator, one note-taker.** A facilitator who is also writing notes stops watching, and
what the analyst does with their mouse before they do anything is half the data.

Recording: screen and audio, with written consent, **and nothing else**. No faces, no names in the
file, no real applicant data anywhere — the sandbox tenant's data is synthetic and must stay that
way. Delete the recordings when the report is written and say in the report that you did.

**What a facilitator must not say.** This is most of the discipline:

- never name a screen, a menu, or a button
- never answer "is this right?" — answer "what do you think?" and wait
- never explain a diagnostic. The diagnostic is under test
- say nothing at all for at least 30 seconds of silence. Silence is the measurement; filling it
  destroys the data point and is the hardest part of facilitating
- when you do have to intervene, **record it as an assist and say what you said**. An unrecorded
  assist turns an Abandoned into an Unaided and is the single easiest way to produce a false pass

Order the tasks 1 → 6 as written. They build on each other, and shuffling them to "avoid order
effects" with three participants trades a known bias for an unknown one.

---

## Turning it into "fixes scheduled"

M5-10's exit criterion is **RK1 assessed and fixes scheduled**, which means a list with owners and
dates, not a report.

1. **Every assist becomes a candidate.** The facilitator said something the product should have;
   write down what, and where it would have to appear.
2. **Group by cause, count by analyst.** Three analysts blocked at the same place is one fix.
3. **Decide the three missing mitigations explicitly, against the evidence.** This is the output
   that matters:
   - **Policy-sentence view** — ship it if analysts could read a rule but not write one. It is a
     reading aid and will not fix authoring.
   - **Starter packs** — ship it if Task 4's successes copied an existing rule and its failures
     started from nothing. That is the cheapest of the three and the one most likely to move Task 4.
   - **Editable decision tables** — the expensive one, and the one to resist until the evidence is
     specific. `PolicyStructure`'s own comment gives the honest reason it is read-only: editing
     means regenerating PSF from the grid, and PSF is where an author's comments live — the sentence
     explaining why a threshold is 21 and not 18 does not survive a round trip through the IR.
     **Ship it only if analysts failed Task 4 on the table syntax rather than on the expression**,
     because if BEL is what defeated them, a grid whose cells are still BEL unary tests changes
     nothing and costs a quarter.
4. **Re-run Tasks 3 and 4 with one new analyst after the fixes.** Not the same analyst: the second
   time, they are no longer naive, and a product that works for somebody who has used it before is
   the thing being tested in the first place.

The report says, in one line: **RK1 materialised / did not materialise, on this evidence** — and
then the assist list. Nothing else in it matters.
