# GramText — Field Pilot & Impact Report (template)

## 1. Summary
- **Pilot:** [dates], at [village / centre], with partner NGO [name].
- **Participants:** [N] ([n] women, [n] aged over 60, [n] who cannot read Hindi).
- **Headline result:** [e.g. "82% of labels were read correctly; 9 of 10 participants would use it again"].

## 2. Usage metrics (from `GET /api/metrics`)
| Metric | Value |
|---|---|
| Total scans | `scans.total` |
| Successful scans | `scans.successful` (… %) |
| Read by our STR model vs Gemini backup | `scans.by_engine` |
| Average STR confidence | `scans.str_avg_confidence` |
| Average scan time | `scans.avg_latency_ms` ms |
| Read-aloud requests | `tts.total` |
| Feedback: "read correctly" rate | `feedback.helpful_rate` |
| Corrections submitted (new training data) | `feedback.corrections_submitted` |
| Unique devices | `unique_devices` |

## 3. Model accuracy (from `ml/eval.py`)
| Test set | Character accuracy | Word accuracy |
|---|---|---|
| Synthetic held-out (Hindi) | | |
| Synthetic held-out (English) | | |
| Real crops: field pilot / IIIT-ILST | | |

## 4. Community feedback (from the feedback forms)
| Question | Average (1–5) |
|---|---|
| Easy to use | |
| Voice was clear | |
| Understood the label | |
| Would use again | |

Task completion without help: medicine label [..%], notice [..%], sign board [..%].
Key quotes, translated: "..."

## 5. SDG outcomes
- **SDG 3:** [n] medicine labels read aloud. [x]% of participants understood the dosage instructions.
- **SDG 4:** [n] participants with limited literacy accessed printed information on their own.
- **SDG 10:** [n] government-scheme notices made accessible to [n] users.

## 6. Problems found and fixes
| Problem | Fix / next step |
|---|---|

## 7. Recommendations
[e.g. fine-tune on the pilot's corrected crops; add Marathi; offline mode]

*Signed:* [team], [faculty guide], [NGO coordinator]
