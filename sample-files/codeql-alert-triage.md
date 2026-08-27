# CodeQL Code Scanning Alert Triage — asset-mgt-render-free

Branch scanned: `develop` · 35 open alerts (16 High, 19 Medium) · Every alert individually verified against the actual flagged line (confirmed with `cat -n`, not just the CodeQL summary), tracing each value back to where it actually originates.

## Table of Contents

- [Summary](#summary)
- [✅ Dismiss as False Positive — 13 alerts](#-dismiss-as-false-positive--13-alerts)
  - [Path traversal cluster — "Uncontrolled data used in path expression" (8 alerts)](#path-traversal-cluster--uncontrolled-data-used-in-path-expression-8-alerts)
  - [Sensitive data storage (2 alerts)](#sensitive-data-storage-2-alerts)
  - [Test-file findings (2 alerts)](#test-file-findings-2-alerts)
  - [ReDoS (1 alert)](#redos-1-alert)
- [🟡 Dismiss as Accepted Risk — 2 alerts](#-dismiss-as-accepted-risk--2-alerts)
- [🔧 Fix — Genuine Findings — 19 alerts](#-fix--genuine-findings--19-alerts)
  - [Information exposure through exception (5 alerts)](#information-exposure-through-exception-5-alerts)
  - [Log Injection (10 alerts, corrected priority)](#log-injection-10-alerts-corrected-priority)
  - [Path/log crossover (1 alert)](#pathlog-crossover-1-alert)
- [🤔 Decide — Needs a Real Judgment Call — 1 alert](#-decide--needs-a-real-judgment-call--1-alert)
- [Suggested order of operations](#suggested-order-of-operations)
- [How to Dismiss the False Positives in GitHub](#how-to-dismiss-the-false-positives-in-github)
- [How to Use the `.security` Folder](#how-to-use-the-security-folder)

**How to use this doc:** each alert has a recommended disposition — **Dismiss (false positive)**, **Dismiss (accepted risk)**, **Fix**, or **Decide** (needs a judgment call from you, not a clear-cut answer). Alert numbers (`#1`–`#35`) match the numbers shown in the GitHub UI, so you can select and bulk-dismiss directly from this list.

---

## Summary

| Disposition | Count | Alerts |
|---|---|---|
| ✅ Dismiss — false positive | 13 | #1, #2, #3, #9, #12, #14, #15, #16, #17, #18, #19, #20, #21 |
| 🟡 Dismiss — accepted risk (low-value target) | 2 | #11, #13 |
| 🔧 Fix — genuine issue | 19 | #4, #5, #6, #7, #8 (exception exposure) + #22–#35 (log injection, all 14) |
| 🤔 Decide — needs a real judgment call | 1 | #10 |
| **Total** | **35** | |

Every count and file:line pairing below was re-verified with `cat -n` against the actual repo — including catching my own miscount on the first pass (the errorbeacon Log Injection finding is 3 separate alerts on one line — #31/#32/#33 — for 3 distinct taint sources, not 1; I'd only listed #33 the first time through).

---

## ✅ Dismiss as False Positive — 13 alerts

These are cases where CodeQL's generic taint-tracking doesn't recognize a hand-written guard as a "sanitizer," or where it's flagging something that is actually the industry-standard correct pattern. I traced the data flow by hand for each of these; they're safe to close now.

### Path traversal cluster — "Uncontrolled data used in path expression" (8 alerts)

| # | Location | Why it's safe |
|---|---|---|
All 8 confirmed with exact line numbers via `cat -n` (not approximate):

| # | File:Line | Exact flagged line | Why it's safe |
|---|---|---|---|
| #21 | `restore_impl.py:59` | `if not os.path.exists(filepath):` | `_restore_backup_impl(filepath, ...)`'s `filepath` parameter, when reached via the HTTP route (`restore_from_local`), was already produced by `get_backup_filepath()` — `os.path.basename()`'d **and** checked against a stored index of known-good backup filenames. |
| #20 | `export_tasks.py:108` | `export_date = datetime.date.fromtimestamp(os.path.getmtime(disk_path)).isoformat()` | 6 lines above: `if task_id != os.path.basename(task_id) or ".." in task_id: return None`. Explicit guard, checked before `disk_path` is ever built. |
| #19 | `export_tasks.py:102` | `if os.path.isfile(disk_path):` | Same function, same guard as #20. |
| #18 | `create.py:209` | `os.remove(filepath)` | `delete_backup()` does `safe_name = os.path.basename(filename)` two lines above — `basename()` strips any `/` or `..`, so the result can't escape the backup directory. |
| #17 | `create.py:208` | `if os.path.exists(filepath):` | Same function, same guard, one line up. |
| #16 | `create.py:201` | `if not os.path.exists(filepath):` | Inside `get_backup_filepath()` itself — `filepath` is built from `safe_name` **after** it's already been checked against the backup index (see #21). |
| #15 | `backup_api.py:102` | `return FileResponse(filepath, media_type="application/gzip", filename=filename)` | This route does `filepath = backup_service.get_backup_filepath(filename)` (the same validated function as #21/#16) immediately before this line. |
| #14 | `audit_api.py:101` | `path=fallback["disk_path"]` | **Traced end-to-end:** `fallback = find_export_on_disk(task_id)`, and `find_export_on_disk` (`export_tasks.py:64`) is the *exact same function* as #19/#20 — same `os.path.basename(task_id)`/`".." in task_id` guard, confirmed by `grep`ing the import (`from tasks.export_tasks import ... find_export_on_disk`). Not a lookalike pattern — it's literally the same function. |

**Suggested dismissal reason to paste in GitHub:** *"False positive — value is basename()'d and/or validated against a known-good index before use; see `get_backup_filepath()` / the `os.path.basename(task_id)` guard in this function."*

### Sensitive data storage (2 alerts)

| # | Location | Why it's safe |
|---|---|---|
| #9 | `backend/api/auth_api.py:109` | This is `response.set_cookie(key="access_token", value=token, httponly=True, samesite="lax", secure=settings.is_production, ...)`. Storing a session token in an **HttpOnly, SameSite, Secure-in-prod cookie** is the textbook-correct way to persist a session token — CodeQL's generic "sensitive value → persistent storage" heuristic doesn't know the difference between this and, say, writing a password to a plaintext file. |
| #1 | `frontend/js/auth.js:43` | `persistSession()` only stores `user_id, name, username, role, department, expires_at, needs_password_reset` in `localStorage` — no password, no JWT, no token. A comment in the file confirms the real credential lives in the HttpOnly cookie, not here. |

**Suggested dismissal reason:** *"False positive — token is stored as an HttpOnly/Secure/SameSite cookie (#9), or no secret is actually present in the stored object (#1)."*

### Test-file findings (2 alerts)

| # | Location | Why it's safe |
|---|---|---|
| #3 | `frontend/tests/pages-load.test.mjs:75` | Test-only code, never shipped. GitHub's own scanner already auto-tagged this "Test." |
| #2 | `frontend/tests/pages-load.test.mjs:59` | Same file, same reasoning. |

**Suggested dismissal reason:** *"Used in tests."*

### ReDoS (1 alert)

| # | Location | Why it's likely safe |
|---|---|---|
| #12 | `errorbeacon/app/main/__init__.py:165` | `re.fullmatch(r'(?i)(\d+)(m|h|d|w)', window.strip())`. Polynomial/catastrophic backtracking needs *nested or overlapping* quantifiers (e.g. `(\d+)+`); a single `\d+` followed by one required literal-class character doesn't have that shape, so worst-case matching time stays linear in input length. |

**Optional (cheap) hardening even though it's a false positive:** nothing currently caps the length of `window` before it hits the regex. Add a simple `if len(window) > 32: raise HTTPException(400, ...)` above the regex call as defense-in-depth — costs one line, removes any doubt.

---

## 🟡 Dismiss as Accepted Risk — 2 alerts

Real findings, technically correct, but low-value targets that aren't worth "fixing" so much as consciously accepting.

| # | Location | Reasoning |
|---|---|---|
| #11 | `backend/scripts/gdrive_oauth_setup.py:83` | `print()`s the OAuth client secret to the terminal — but this is a one-time, local-only, interactive CLI setup script a developer runs on their own machine to get credentials into their own `.env`. It never touches the app's logging pipeline. Fine to dismiss as "won't fix." |
| #13 | `scripts/security-boundary-test.py:150` | `"evil.example.com" in allow_origin` is an "incomplete substring sanitization" pattern (e.g. `evil.example.com.attacker.com` would also match) — but this is inside the repo's *own external security-verification test script*, not production code. Worst case, this one check in the test tool gives a wrong pass/fail; it doesn't create a vulnerability in the app itself. Cheap to fix if you want tidiness (swap to exact match or proper origin parsing), otherwise safe to dismiss as "used in tests." |

---

## 🔧 Fix — Genuine Findings — 19 alerts

These are real. I traced every one down to the exact flagged line and, for the exception-exposure cluster, followed the data back to its actual source — two of them turned out to be more specific (and more serious) than a first pass suggested.

### Information exposure through exception (5 alerts)

Exact flagged line confirmed for each with `cat -n`:

| # | File:Line | Exact flagged line |
|---|---|---|
| #4 | `backend/api/backup_api.py:92` | `return entry` |
| #5 | `backend/api/backup_api.py:163` | `return result` |
| #6 | `backend/api/backup_api.py:198` | `return result` |
| #7 | `backend/main.py:480` | `return status` |
| #8 | `backend/main.py:521` | `return {"ready": redis_status["ok"], "dependencies": {"redis": redis_status}}` |

Notice these are all `return` statements on what looks like the **success path** — not the `raise HTTPException(...)` lines you'd expect. That's because the leak isn't in the exception handling itself; it's that raw exception text gets *embedded inside a dict that's returned as a normal 200 response*. I traced each one to its source:

**#4 — `create_backup_now` → `backup_api.py:92`**
`create_backup()` (`backend/services/backup_service/create.py:166-168`) does:
```python
except Exception as exc:
    logger.exception("backup_service: Google Drive upload failed for %s", filename)
    entry["gdrive_error"] = str(exc)
```
If the Google Drive upload fails, the raw exception text lands in `entry["gdrive_error"]`, and that `entry` is returned as a normal (200) response body — no exception is even raised. A super-admin viewing this response sees whatever Google's API/OAuth client raised verbatim.

**#5 — `restore_from_local` → `backup_api.py:163`, and #6 — `restore_from_upload` → `backup_api.py:198`**
Both call `restore_backup()`, which calls `_restore_backup_impl()`, whose final return (`restore_impl.py:595-603`) includes:
```python
return {
    "restored_from": os.path.basename(filepath),
    "safety_backup": safety_entry,        # <- same `entry` dict as #4 above
    "schema_status": schema_status_after, # <- see #7 below
    ...
}
```
`safety_backup` is the exact same `create_backup()` return value from #4 — so the Google Drive error leak rides along on every restore response too. `schema_status` is `get_schema_status()`'s return value — see #7.

**#7 — `/readyz` → `main.py:480` — the most important one to fix**
`get_schema_status()` (`backend/database.py:732`):
```python
"reason": f"Could not reach the database to check its migration state: {exc}",
```
This raw DB-connection exception text (can include hostnames/connection details) is returned directly by `/readyz`. **I confirmed `/readyz` has no `Depends(...)` auth at all — it's a fully public endpoint.** Anyone can hit it and, if the DB is unreachable, see the raw connection error. This is also the same `schema_status` object embedded in #5/#6's restore responses.

**#8 — `/health/dependencies` → `main.py:521` — also unauthenticated**
`check_redis_health()` (`backend/celery_app.py:97`): `return {"ok": False, "latency_ms": None, "error": str(exc)}` — same pattern, also confirmed to have no auth dependency.

**Fix:** for #4/#5/#6, stop putting `str(exc)` into fields that get returned to the client — log it (`logger.exception(...)` is already called right there) and store something generic like `"gdrive_error": "upload failed, see server logs"` instead. For #7/#8, same idea for the `"reason"`/`"error"` fields — these are the two I'd fix first since they're the only genuinely public ones.

### Log Injection (10 alerts, corrected priority)

Exact flagged lines confirmed for every entry:

| # | File:Line | Exact line | Source of the value | Auth context |
|---|---|---|---|---|
| #22 | `auth_service.py:113` | `logger.warning("Login failed: no matching account", extra={"identifier": identifier})` | Raw login-form `identifier` | **Pre-auth — anonymous, attacker-controlled** |
| #23 | `auth_service.py:157` | `logger.warning("Login failed", extra={"identifier": identifier})` | Same `identifier`, wrong-password branch | **Pre-auth — anonymous, attacker-controlled** |
| #25 | `auth_service.py:621` | `logger.info("Password reset requested for unknown identifier", extra={"identifier": req.identifier.strip()})` | Forgot-password form field | **Pre-auth — anonymous, attacker-controlled** |
| #34, #35 | `telemetry_api.py:623/624` | `logger.error("Uncaught client-side JS error: %s", payload.message, extra={...})` | `payload.message`/`path`/`stack` — raw client POST body | Likely unauthenticated client telemetry endpoint |
| #31, #32, #33 | `errorbeacon/app/main/__init__.py:123` — **confirmed all 3 point to the identical line**: `log.info('Event ingested: incident=%s occurrence=%s queued=%s silenced=%s app=%s component=%s request_id_correlated=%s',i,o,q,s,event.app,event.component,_correlated)` | 3 distinct taint sources feeding the same sink line: `event.app`, `event.component`, and `_ctx.get('request_id_correlated', True)` | ErrorBeacon ingests events *from other apps* — `event.app`/`event.component` are attacker-influenceable if any upstream client can set them |
| #24 | `auth_service.py:563` | `logger.info("Password updated", extra={"target_user_id": target.id, "changed_by": current_user["email"]})` | Authenticated admin's own email + a target user ID | Authenticated, low risk |
| #26 | `checkout_service.py:126` | `extra={"user": user["email"], ...}` | Authenticated user's own email | Authenticated, low risk |
| #27, #28, #29 | `extension_service.py:195/407/496` | `extra={"user": user["email"], ...}` | Authenticated user's own email | Authenticated, low risk |

**Correction from my earlier pass:** I'd previously described the `auth_service.py` alerts as "an authenticated user's own email." That's wrong for #22/#23/#25 — I re-checked and all three sit in **pre-authentication** code paths (failed login, forgot-password), where `identifier` is raw, anonymous, attacker-supplied input. Those three belong in the same priority tier as the telemetry ones (#34/#35), not with the lower-risk authenticated-actor group (#24/#26/#27/#28/#29).

**What's happening:** an unescaped, caller-controlled string goes into `logger.x(...)`. A value containing a newline can forge what looks like a separate, fake log line — genuinely relevant here since this repo's own alerting (`infra/main.bicep`'s `alertReadyzFailing` rule) already greps the raw container log stream for specific text patterns, so a forged line is a plausible way to spoof or suppress a monitoring alert.

**Fix (one change, reused everywhere):**
```python
_LOG_CONTROL_CHARS = str.maketrans({"\n": "\\n", "\r": "\\r"})

def sanitize_for_log(value) -> str:
    return str(value).translate(_LOG_CONTROL_CHARS)
```
Wrap the values at each site (`sanitize_for_log(identifier)`, `sanitize_for_log(payload.message)`, etc.). Do the pre-auth ones (#22, #23, #25, #34, #35) first.

### Path/log crossover (1 alert)

| # | Location | Exact line |
|---|---|---|
| #30 | `backend/services/backup_service/restore_impl.py:330` | `logger.warning("backup_service: RESTORE COMPLETE from %s -- database has been replaced.", os.path.basename(filepath))` |

Value is already trimmed to a bare filename via `os.path.basename()` (no `/`), but `basename()` doesn't strip newlines, so it's not fully immune to the same log-forging concern above. Low risk (only reachable by a super-admin who already chose the backup file), but cheap to fold into the same `sanitize_for_log()` fix rather than leave as a one-off exception.

---

## 🤔 Decide — Needs a Real Judgment Call — 1 alert

| # | Location |
|---|---|
| #10 | `backend/alembic/versions/0002_bootstrap_root_admin.py:158` |

**What's happening:** this migration generates the root admin's initial password and does `print(f"  password: {bootstrap_password}", file=sys.stderr)`, with a comment explicitly stating the intent: *"shown ONLY ONCE, right now... not in any log after this line."*

**Why it's not a clean dismiss:** on Azure Container Apps, `stderr` **is** captured into `ContainerAppConsoleLogs_CL` — this exact repo's own alerting rule (`alertReadyzFailing` in `infra/main.bicep`) already greps that same log stream. So the stated intent ("not in any log") is very likely not what actually happens in production — the generated root-admin password may be landing in centralized, retained cloud logs, visible to anyone with Log Analytics read access on the subscription.

**This is worth a real conversation, not a quick fix or dismiss**, because the options have real tradeoffs:
- Write the password to a locked-down local file on the deploying machine instead of stdout/stderr.
- Require an interactive confirmation/second step to reveal it, rather than broadcasting it on bootstrap.
- Or: explicitly accept the risk (bootstrap is a one-time, tightly-controlled deploy event) and document *who* has Log Analytics access and how long those logs are retained, then dismiss with that reasoning recorded.

I'd flag this to whoever owns infra/deployment before you make a call either way.

---

## Suggested order of operations

---

## How to Dismiss the False Positives in GitHub

CodeQL alerts are dismissed from the **Security → Code scanning** tab, not by editing a file in the repo — there's no `.security` mechanism for CodeQL yet (see the next section for why, and what to do if you want one).

**Bulk dismissal (fastest — do this for the 13 false positives + 2 accepted-risk alerts):**

1. Go to `https://github.com/samuelgodson55/asset-mgt-render-free/security/code-scanning?query=is%3Aopen+branch%3Adevelop`.
2. Tick the checkboxes next to the alerts you're closing. You can do this in batches — e.g. select all 8 path-traversal ones (#14–#21) together since they share one reason.
3. A **"Dismiss"** dropdown button appears above the list once anything is checked. Click it.
4. Pick a reason from GitHub's fixed set:
   - **False positive** — for #1, #2, #3, #9, #12, #14, #15, #16, #17, #18, #19, #20, #21
   - **Used in tests** — alternative reason for #2/#3 specifically, if you'd rather tag them that way than "false positive"
   - **Won't fix** — for #11, #13 (the accepted-risk pair)
5. GitHub prompts for an optional comment — **always fill this in**, even though it's optional. This is the only record of *why* a specific alert was closed; six months from now neither you nor anyone else auditing the repo will remember. Paste in the one-line reasoning from this doc's table, e.g. for #21: *"filepath is basename()'d and validated against the backup index in get_backup_filepath() before this line runs — see restore_impl.py's caller in backup_api.py."*
6. Confirm. Dismissed alerts move to the "Closed" count and drop out of the default `is:open` view, but stay visible (and reversible — anyone can reopen) under **Closed** on that same page.

**One at a time:** open an individual alert (click its title) and the same Dismiss button + reason + comment flow appears on the alert's own page, with more room to write a longer justification if you want.

**Important:** a dismissal here is scoped to that specific alert instance, not the underlying CodeQL rule — if the same pattern shows up again in a future scan at a *different* line (e.g. someone adds a ninth backup-path function next month), CodeQL will raise a **new** alert and you'll need to dismiss that one too. GitHub doesn't currently support "always dismiss this rule for this codebase" the way some other scanners do — a per-line dismissal is deliberate and, honestly, correct here, since a superficially similar line elsewhere might *not* have the same guard in front of it.

---

## How to Use the `.security` Folder

Right now, `.security/` contains exactly one file — `pip-audit-exceptions.json` — and it's specific to the `pip-audit` gate, not a general-purpose security-exceptions folder. It's read by `scripts/security/pip_audit_gate.py` during the `backend` CI job, not by anything CodeQL-related. Its shape:

```json
{
  "_readme": [ "... explanation, kept in the file itself ..." ],
  "backend": [],
  "errorbeacon": []
}
```

Each of the `"backend"`/`"errorbeacon"` arrays holds one entry per accepted-but-unfixed pip-audit finding, and every entry is required to have:

| Field | Purpose |
|---|---|
| `id` | The pip-audit finding ID (`PYSEC-...` or `GHSA-...`) |
| `reason` | Why this stays open instead of being fixed now |
| `added` | When the exception was created |
| `review_by` | An ISO date in the future — **the exception expires on this date** |
| `approved_by` | Who signed off on accepting the risk |

The key behavior, per `docs/SECURITY_CI_POLICY.md`: an exception whose `review_by` date has passed is treated as if it doesn't exist — the finding blocks the build again automatically, and the gate script fails loudly naming which entry needs a fresh look. It's a **scheduled re-check, never a permanent opt-out**. There are no real entries in the file right now — it's empty on purpose, meaning there are no currently-accepted pip vulnerabilities.

**CodeQL doesn't have an equivalent file today.** Per the CodeQL row in `docs/SECURITY_CI_POLICY.md`, it's deliberately still in "report-only" mode — `codeql-action/analyze` never fails the build on its own, so there's been no reason yet to build a dated-exceptions mechanism for it the way pip-audit has one. Dismissing an alert in the Security tab (previous section) is the entire mechanism right now, and unlike the pip-audit JSON file, **that dismissal never expires or gets re-flagged automatically** — it's closed until someone manually reopens it.

**If you want CodeQL exceptions to have the same "dated, reviewed, auto-expiring" discipline as pip-audit** — worth doing once you flip branch protection to actually enforce the CodeQL check (see the last step below) — the repo's own policy doc tells you the shape to copy: add a `.security/codeql-exceptions.json` following the same `id`/`reason`/`added`/`review_by`/`approved_by` pattern (using the GitHub alert number as `id`), and a small script that runs in CI, reads it, and re-flags anything past its `review_by` date. That's not built yet — it's a reasonable follow-up once the initial 35-alert backlog above is cleared and you're deciding how to keep it clean going forward, not something you need before dismissing today's alerts.

1. **Fix `/readyz` and `/health/dependencies` first** (#7, #8) — confirmed unauthenticated, confirmed leaking raw DB/Redis exception text to anyone who hits them. Highest real-world exposure in the whole list, smallest fix.
2. **Fix the pre-auth log-injection sites** (#22, #23, #25, plus #34/#35 in telemetry) — anonymous, attacker-reachable inputs going straight into logs. One shared `sanitize_for_log()` helper covers all of these.
3. **Bulk-dismiss the 14 false positives** (and the 2 accepted-risk ones) — 5 minutes, clears most of the noise.
4. **Fix the remaining exception-exposure leaks** (#4, #5, #6 — the `gdrive_error`/`schema_status` fields riding along on success responses) and the lower-priority, authenticated-only log-injection sites (#24, #26–#30, #31–#33).
5. **Raise #10 (bootstrap password logging)** with whoever owns the infra/deploy pipeline before deciding — real tension between "shown once" intent and Container Apps' stderr capture into Log Analytics.
6. Once the backlog is at zero (or everything remaining has an open fix in progress), flip branch protection to **require the CodeQL status check** — that's the actual "P1 closed" condition per the plan already documented in `ci.yml`'s own comments, not just having the scanner running.
