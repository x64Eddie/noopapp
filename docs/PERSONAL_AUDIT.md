# Sleep metrics audit, WHOOP gap check, strength-log roadmap (2026-09)

Findings only; no scoring formula is changed by this document. A change to any of these formulas
must land on the Swift twin too (AGENTS.md parity contract) and shifts historical scores, so each
is a separate decision.

## 1. Sleep: how the "Rest" score is built

`RestScorer.rest` (android/…/analytics/AnalyticsEngine.kt):
`0.50·duration + 0.20·efficiency + 0.20·restorative + 0.10·consistency`

| Term | Implementation | Verdict |
|---|---|---|
| Duration | asleep h ÷ personal need; need = 75th pct of 28 nights, floored at 8 h (adult), capped 9.5 h | **Sound.** NSF adult range 7–9 h (Hirshkowitz et al. 2015, *Sleep Health* 1(1):40–43); upper-quartile + floor stops a chronic short sleeper from lowering their own need. |
| Efficiency | TST ÷ time in bed | **Standard definition.** Caveat: wrist devices detect sleep well but wake poorly (epoch specificity 0.18–0.54, Chinoy et al. 2021, *Sleep* 44(5):zsaa291), so efficiency reads high. |
| Restorative | (deep+REM) share ÷ 0.50, scaled down when deep < 13 % | **Weakest term.** (a) It rests on stage classification, the least reliable wearable output: Chinoy 2021 found stage agreement "inconsistent" across devices, and NOOP's own stager notes a 65–73 % EEG-free ceiling (Walch et al. 2019, *Sleep* 42(12)). (b) The 0.50 target is at the top of the normal range. Adult SWS falls with age and REM is ~20–25 % (Ohayon et al. 2004, *Sleep* 27(7):1255–73), so a healthy adult at 15 % deep + 22 % REM scores 74/100 on this term every night. |
| Consistency | `VitalityEngine.sleepConsistency` = 1 − CV of nightly **duration** over 28 nights | **Measures the wrong thing.** It ignores timing: 7 h from 22:00 and 7 h from 03:00 on alternate nights scores as perfectly consistent. The validated metric is the Sleep Regularity Index, the probability of being in the same sleep/wake state 24 h apart (Phillips et al. 2017, *Sci Rep* 7:3216). SRI independently predicts all-cause mortality better than duration (Windred et al. 2024, *Sleep* 47(1):zsad253; n = 60 977). WHOOP's own "Sleep Consistency" is timing-based. |

### Recommended changes, in priority order
1. **Replace duration-CV with SRI** over the trailing 7–14 days, computed from the in-bed/asleep
   sessions already stored (minute grid, `SRI = 200·P(same state at t and t+24h) − 100`, clamped
   0..100). Show it on the Sleep screen with the Windred 2021 bands (≤ 51 irregular, 52–70 moderate,
   ≥ 71 regular). Kotlin + Swift twins, an oracle test, and a changelog note that Rest shifts.
2. **Make the restorative target age-aware.** Use Ohayon 2004 norms instead of a flat 0.50,
   or cut the term's weight to 0.10 and give the rest to duration, which is the best-measured input.
3. **Report "accuracy dropped" concerns with data.** The staging code already warns that WHOOP 4.0
   motion is too sparse to stage reliably (#345). Test Centre → Sleep & Rest mode logs
   `sleep-motion … sparse=` per night. A spell of `sparse=true` nights would explain worse sleep reads.
   This needs an export from the phone before any formula is blamed.

## 2. WHOOP features (2025–26) vs this app

| WHOOP | Here | Worth doing? |
|---|---|---|
| Shareable GPS activity summary (2026) | Route now drawn in the workout detail sheet; GPX/FIT export exists | Next small step: a share card image (route + distance/duration/pace). |
| Improved HR signal processing (2026) | N/A (on-strap/cloud algorithm) | No. |
| Advanced Labs, clinician visits, medical records, AI memory (2025–26) | Out of scope: cloud/clinical | No. The open-wearables backend is where labs would live. |
| Healthspan / WHOOP Age | `VitalityEngine` (upstream) | Already covered. |
| Stress monitor | `DaytimeStress` (upstream) | Already covered. |
| Strength Trainer (muscular load) | Lift Log schema + iOS UI upstream; **no Android UI/DAO** | Yes; see §3. |

## 3. Jefit-style strength logging

Jefit's core (per its store listing / docs): routines, per-set weight × reps × RPE, automatic rest
timer, per-exercise progress graphs, estimated 1RM, weekly sets per muscle, a muscle-recovery map,
and live watch sync with HR + calories.

Upstream already has the data model on Android (`data/LiftEntities.kt`, schema v46): sets carry
`startTs`, `endTs`, `restSec`, `rpe`, and muscles. `analytics/LiftMetrics.kt` has volume load, session
RPE load, e1RM, and per-muscle counts. Only the Android DAO and screens are missing (upstream
deliberately left them for someone with a device).

The HR correlation Jefit can't do falls out of the timestamps: for each set, the strap's HR over
`[startTs, endTs]` (peak, time-in-zone) and over `restSec` (heart-rate recovery, how far HR fell
before the next set). Per exercise over weeks, that gives cardiovascular cost at a given load.

Suggested phases:
1. Android `LiftDao` + a minimal logger inside an active Strength workout: pick exercise, log
   weight/reps/RPE, and "set done" starts the rest timer. The strap double-tap to advance already
   exists upstream.
2. Per-set HR overlay in the workout detail sheet (HR curve with set bands; peak + recovery per set).
3. Progress: per-exercise e1RM/volume trend, weekly sets per muscle, and HR-cost-per-kg trend.

Until then, the "two apps" gap can be narrowed through Health Connect. NOOP already imports HC
exercise sessions (`ingest/HealthConnectImporter.kt`), so a Jefit session would line up with strap HR
in NOOP's workout list. Whether Jefit on Android writes to Health Connect is **unconfirmed**. Check
Settings → Health Connect → App permissions for Jefit with Exercise write access.
