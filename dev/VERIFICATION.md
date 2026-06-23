# Verification Runbook — `page-property-links`

**Scope:** manual verification. These checks exercise behaviour only a running wiki exhibits —
live Solr indexing, the refactoring job, real property storage — so they cannot be covered by the
unit tests and require a person to interact with XWiki.
**Target:** XWiki 18.x (minimum 18.0.0); verified on `xwiki:stable-postgres-tomcat` + `postgres:18`.

---

## 0. Prerequisites

- Docker accessible in your shell. On Windows, run the commands below from WSL or Git Bash.
- Built JAR at `target/page-property-links-1.0.0-SNAPSHOT.jar`.
  If it is missing: `bash dev/build.sh clean package`

---

## 1. Build and Deploy

### 1a. Build

```bash
bash dev/build.sh clean package
```

Expected: `BUILD SUCCESS`, JAR at `target/page-property-links-1.0.0-SNAPSHOT.jar`.

### 1b. Deploy — option A: your existing running instance

If you already have XWiki running (e.g. an existing instance on port 8080), skip `docker compose up` and run:

```bash
bash dev/deploy.sh YOUR_CONTAINER
```

Replace `YOUR_CONTAINER` with the output of `docker ps` for your XWiki container.

### 1b. Deploy — option B: spin up the dev compose stack

**CAUTION:** This starts a separate XWiki on port 8080. Do NOT use if your live instance is already on 8080.

```bash
docker compose -f dev/docker-compose.yml up -d
# First start: XWiki runs its installer (several minutes on first boot).
# Snapshot recent logs (returns immediately):
docker logs --tail 40 $(docker compose -f dev/docker-compose.yml ps -q xwiki)
# Or block until the home page is ready (exits on HTTP 200; ~10 min cap):
timeout 600 bash -c "until curl -fsSL -o /dev/null http://localhost:8080/; do sleep 5; done" && echo XWIKI_READY || echo "not ready after 10m"
# Once running, deploy:
bash dev/deploy.sh
```

### 1c. Wait for startup

After `docker restart`, give it time to come back up. Check readiness without tailing:

```bash
# Recent logs (returns immediately):
docker logs --tail 40 dev-xwiki-1
# Block until the home page answers HTTP 200 (~10 min cap):
timeout 600 bash -c "until curl -fsSL -o /dev/null http://localhost:8080/; do sleep 5; done" && echo XWIKI_READY || echo "not ready after 10m"
```

In the logs, look for `Server startup in [NNN] milliseconds` (Tomcat). Note: `http://localhost:8080/`
may briefly return 404 during init — the readiness check above follows redirects and waits for a real 200.

On a **fresh** data volume, once it stops 404-ing XWiki may show an *"XWiki is initializing (N%)"*
progress page (HTTP 202) while it installs the standard flavor. Wait until `/bin/view/Main/` returns
**200** (a normal page) before testing — e.g. poll:

```bash
timeout 600 bash -c "until [ \"\$(curl -s -o /dev/null -w %{http_code} -L http://localhost:8080/bin/view/Main/)\" = 200 ]; do sleep 5; done" && echo INIT_DONE
```

> **Tip:** `docker logs -f dev-xwiki-1` (follow) also works, but you must Ctrl-C to exit it. That only
> stops the log *viewer* — it does **not** stop the container; the wiki keeps running.

### 1d. Confirm the extension loaded

A JAR in `WEB-INF/lib` is a **core extension**, so it appears under
**Administration → Extensions → Core Extensions** (search `Page Property Links` or the id
`org.xwiki.contrib:page-property-links`) — **NOT** under "Installed Extensions" (that tab is only
for Extension-Manager installs).

The most reliable check is that the wiki started cleanly: if any class listed in
`META-INF/components.txt` fails to load, XWiki's servlet-context listener fails and the **entire
ROOT webapp returns 404**, so a working home page already proves the four components registered.
To be explicit, scan the startup log:

```bash
docker logs dev-xwiki-1 2>&1 | grep -i pagepropertylinks
```

Expect **no** `ClassNotFoundException` / `Failed to load component class` lines.

> **Gotcha (learned in dev):** `META-INF/components.txt` must contain **only fully-qualified class
> names**, one per line (optionally `priority:Class`). XWiki's `ComponentAnnotationLoader` does
> **not** support `#` comments — a comment line is treated as a class name, `ClassNotFound`s, and
> takes the whole wiki down (404). Keep it class-names-only.

---

## 2. Test App Setup

Create two minimal test apps using the App Within Minutes wizard or manually via the Object editor.

### App A: Single-value Page property

1. Go to **Applications > App Within Minutes** (or create a class manually).
2. Create class `PPLTest.SingleValueClass` with one field:
   - Field name: `target`
   - Field type: **Page**
   - Relational storage: N/A (single-value is always `StringProperty`)
3. Create an entry (e.g., `PPLTest.SingleValueEntry1`) using `PPLTest.SingleValueClass`.

### App B: Relational multi-value Page property

1. Create class `PPLTest.MultiValueClass` with one field:
   - Field name: `targets`
   - Field type: **Page**
   - **Multi-select: yes** (multiple values allowed)
   - **Relational storage: yes** (stores as `DBStringListProperty` rows — this is the default for multi-value Page properties)
2. Create an entry (e.g., `PPLTest.MultiValueEntry1`) using `PPLTest.MultiValueClass`.

### Create the target page

Create a page at `PPLTest.TargetPage.WebHome` (a nested page, to test `.WebHome` suffix handling).

---

## 3. Backlink Verification (Solr Extractor)

**Goal:** confirm `PagePropertyLinkExtractor` appends the referencing entries to the Solr
`links`/`links_extended` fields, making them appear as native backlinks.

1. In `PPLTest.SingleValueEntry1`, set the `target` field to `PPLTest.TargetPage.WebHome`.
   Save.
2. In `PPLTest.MultiValueEntry1`, set the `targets` field to include `PPLTest.TargetPage.WebHome`
   (and optionally a second value). Save.
3. **No manual reindex needed** — XWiki indexes documents incrementally on save, so within a few
   seconds the two saved entries are indexed and the extractor has added their Page references.
   (If a backlink is missing later, just **edit + save** that entry to re-index it. A full wiki
   reindex lives at **Administration → Search → Solr**: pick the action and click **Apply** — it
   runs asynchronously in a background thread with a queue/ETA — but it's unnecessary for
   freshly-saved test content.)
4. Navigate to `PPLTest.TargetPage.WebHome` → click the **Information** tab (top-right menu or
   breadcrumb extras) → check the **Backlinks** section.

**Expected:** Both `PPLTest.SingleValueEntry1` and `PPLTest.MultiValueEntry1` appear as backlinks.

**If they do not appear:** give the incremental indexer a few seconds; if still missing, **edit +
save** the entry to force a re-index (or full reindex via **Administration → Search → Solr → Apply**).
Then check the XWiki log for errors from `PagePropertyLinkExtractor`:
`docker logs dev-xwiki-1 2>&1 | grep -i pagepropertylinks`

---

## 4. Rename / Move Integrity Verification (Reference Updater)

**Goal:** confirm `PagePropertyReferenceUpdater` rewrites stored property values on rename and
that backlinks remain valid after.

### 4a. Move the target page

1. Go to `PPLTest.TargetPage.WebHome`.
2. Use **More > Rename / Move** (or **Administer > Refactoring**).
3. Rename to `PPLTest.RenamedTarget.WebHome` (keep nested structure to keep `.WebHome` suffix).
4. Click **Rename** (this fires a refactoring job → `DocumentRenamedEvent`).

### 4b. Confirm stored values updated

After the rename job completes (the rename page shows an inline progress bar while it runs):

1. Open `PPLTest.SingleValueEntry1` → edit → check the `target` field value.
   **Expected:** `PPLTest.RenamedTarget.WebHome` (not the old value).
2. Open `PPLTest.MultiValueEntry1` → edit → check the `targets` field values.
   **Expected:** `PPLTest.RenamedTarget.WebHome` in the list (old value replaced).

**Also check via Object editor** (Edit > Objects) to see raw stored values.

### 4c. Confirm backlinks still resolve

Navigate to `PPLTest.RenamedTarget.WebHome` → Information tab → Backlinks.
**Expected:** Both entry pages still listed (Solr re-indexed them when the updater saved them).

### 4d. Check the rename job log

The listener runs **inside the refactoring job thread**, so XWiki's job framework captures its
output into that **job's own log** — shown **in the rename job report in the UI** (the page you land
on after the rename / the job-progress panel). It is **not** flushed to container stdout, so
`docker logs` will NOT show it. In the job report, look for the info-level summary from
`PagePropertyReferenceUpdater`:
```
... Page reference update for rename [PPLTest.TargetPage.WebHome] -> [PPLTest.TargetPage2.WebHome]: scanned=N, changed=M, failed=0
```
(`scanned=0, changed=0` is normal when nothing currently references the renamed page.) If any doc
failed, an **error** line precedes it:
`Failed to update Page references in [<doc>] after rename of [<old>] -> [<new>]`.

**If stored values did NOT update:** check the log for `PagePropertyReferenceUpdater` errors.
Possible causes: rights issue on save (see §6b below), HQL query mismatch, or the property
was not of type `Page` (check `getClassType()` returns `"Page"`).

### 4e. Confirm the "Update links" opt-out is honoured

The rename dialog has an **Update links** checkbox (default on). When **unchecked**, XWiki leaves
incoming *content* links pointing at the old location — and this extension now honours the same
choice for `Page`-property references (they are links too, so they should behave the same way).

1. Point a fresh entry's `Page` property at a target; save.
2. Rename the target, but **uncheck "Update links"** in the dialog before confirming.
3. **Expected:** the entry's stored `Page`-property value is **unchanged** (still the old
   reference), exactly as content links are left unchanged. The rename **job log** (UI report)
   shows **no** `Page reference update` summary for this rename — the listener short-circuits
   before querying.
4. Repeat with the box **checked** → the value is rewritten and the summary line appears.

(Mechanism: the listener reads `MoveRequest.isUpdateLinks()` from the `DocumentRenamedEvent` data;
it defaults to `true`, so ordinary renames are unaffected.)

---

## 5. Non-Relational Storage (flag on)

**Goal:** verify that `pagePropertyLinks.supportNonRelationalStorage=true` enables handling of
`StringListProperty`-backed Page properties (multi-value, relational storage OFF), while NOT
modifying prefix-collision values or plain TextArea content.

### 5a. Enable the flag

The flag is read from **`xwiki.properties`** via the `xwikiproperties` configuration source — a
**file setting, not an Administration-UI option** (deliberate static-config design). `WEB-INF` is
inside the deployed webapp, so the active file is
`/usr/local/tomcat/webapps/ROOT/WEB-INF/xwiki.properties` — **NOT** the permanent dir
`/usr/local/xwiki/data/` (which only holds runtime state: auth keys, the Solr index, extensions).
Append the key and restart:

```bash
docker exec dev-xwiki-1 bash -lc "echo pagePropertyLinks.supportNonRelationalStorage=true >> /usr/local/tomcat/webapps/ROOT/WEB-INF/xwiki.properties" && docker restart dev-xwiki-1
```

The value is read at boot, so a **restart is required**; wait for startup (§1c). Confirm it stuck:
`docker exec dev-xwiki-1 grep pagePropertyLinks /usr/local/tomcat/webapps/ROOT/WEB-INF/xwiki.properties`. To disable, remove the line (or set `=false`) and restart.

### 5b. Create a non-relational multi-value Page property

1. Create class `PPLTest.NonRelClass` with field:
   - Field name: `pagesNR`
   - Field type: **Page**
   - **Multi-select: yes**, **Relational storage: NO** (stores as `StringListProperty`)
2. Create entry `PPLTest.NonRelEntry1`, set `pagesNR` to include `PPLTest.RenamedTarget.WebHome`.
   Save.

### 5c. Add false-positive values (must NOT be modified)

The candidate query (`textValue like '%ref%'`) finds matches by **substring**, so to genuinely test
the exact-match gate you must add a value that *contains* the renamed reference but isn't equal to
it. Use **terminal** pages so the rename in 5e doesn't drag siblings along:

1. Create two terminal pages: **`PPLTest.Coll`** and **`PPLTest.CollX`**.
   `PPLTest.Coll` is a prefix-substring of `PPLTest.CollX` — that's the collision. (A name like
   `PPLTest.CollAccounting` would *not* work: there's no `.` after `Coll`, so `PPLTest.Coll` is not
   a substring of it.)
2. In `PPLTest.NonRelEntry1`, add **both** `PPLTest.Coll` and `PPLTest.CollX` to `pagesNR`. Save.
3. In a TextArea property (or page content) on any page, add the **plain text** `PPLTest.Coll` —
   literally typed, **not** a `[[link]]`. (A wiki link would be handled by XWiki's built-in
   refactoring, not this extension; plain text is the real negative.)

### 5d. Verify backlinks (extractor, flag on)

Edit + save `PPLTest.NonRelEntry1` (or wait for the incremental indexer). On **`PPLTest.Coll`** →
Information → Backlinks: **`PPLTest.NonRelEntry1` appears** (via the non-relational Page reference).
The plain-text TextArea mention must **not** create a backlink — this confirms the extractor ignores
free text, and that the typed `StringListProperty` query does not leak into the `xwikilargestrings`
TextArea rows it shares a table with.

### 5e. Verify rename integrity + the exact-match gate (updater, flag on)

Rename **`PPLTest.Coll` → `PPLTest.CollRenamed`**. After the job, check `PPLTest.NonRelEntry1.pagesNR`:

| Value before | Value after | Why |
|---|---|---|
| `PPLTest.Coll` | `PPLTest.CollRenamed` | exact match → rewritten |
| `PPLTest.CollX` | `PPLTest.CollX` (unchanged) | `like` found the entry, but `equals` rejected the substring |
| plain-text `PPLTest.Coll` (TextArea) | unchanged | not a `Page` property; query is typed to `StringListProperty` |

`PPLTest.CollX` is the real check: the `like '%PPLTest.Coll%'` query **does** surface this entry (its
blob contains `PPLTest.Coll`), so the exact-match gate is genuinely exercised. Confirm with
`docker logs dev-xwiki-1 2>&1 | grep -i "Page reference update"` → expect `changed=1`.

### 5f. Confirm flag-off baseline

Set `pagePropertyLinks.supportNonRelationalStorage=false` (or remove the line), restart.
Move the target again — `PPLTest.NonRelEntry1.pagesNR` must NOT be updated (flag is off;
no `StringListProperty` candidate query is issued).

---

## 6. Verify-the-Unverified Items (cannot be covered by unit tests)

These items require a live XWiki + DB to confirm.

### 6a. HQL candidate queries return rows

Run these in the Groovy console (Administration > Scripting) as a user with **programming right**.
References get rewritten as you rename, so do **not** hardcode an original name — first list what is
actually stored, then bind one of those values.

**Step 1 — list the Page references currently stored** (adjust the `PPLTest.%` prefix to your data):
```groovy
println "single-value:   " + services.query.hql(
  "select sp.value from StringProperty sp where sp.value like :p").bindValue("p","PPLTest.%").execute()
println "relational:     " + services.query.hql(
  "select item from DBStringListProperty p join p.list item where item like :p").bindValue("p","PPLTest.%").execute()
println "non-relational: " + services.query.hql(
  "select p.textValue from StringListProperty p where p.textValue like :p").bindValue("p","%PPLTest%").execute()
```
Pick a real value from the output (e.g. `PPLTest.CollEDIT2.WebHome`) for `:ref` below.

**Query 1 — StringProperty (single-value):**
```groovy
def ref = "PASTE_A_VALUE_FROM_STEP_1"
println "StringProperty hits: " + services.query.hql(
  "select sp.id.id from StringProperty sp where sp.value = :ref").bindValue("ref", ref).execute()
```

**Query 2 — DBStringListProperty (relational multi-value):**
```groovy
def ref = "PASTE_A_VALUE_FROM_STEP_1"
println "DBStringListProperty hits: " + services.query.hql(
  "select p.id.id from DBStringListProperty p where :ref in elements(p.list)").bindValue("ref", ref).execute()
```

**Query 3 — StringListProperty (non-relational; flag must be ON):**
```groovy
def ref = "PASTE_A_VALUE_FROM_STEP_1"
println "StringListProperty hits: " + services.query.hql(
  "select p.id.id from StringListProperty p where p.textValue like :pat").bindValue("pat", "%"+ref+"%").execute()
```

**Expected:** each returns the object id(s) for the matching entries. The non-relational `textValue`
field is **confirmed** against the 18.4.1 mapping: `StringListProperty → xwikilargestrings.XWL_VALUE`,
a `|`-delimited blob (which is why Query 3 uses `like`). Storage by shape: single → `xwikistrings`;
relational items → `xwikilistitems`; non-relational blob → `xwikilargestrings`.

**If a query returns 0 unexpectedly:** you almost certainly bound a stale/renamed value — re-run
Step 1 to see the current references. To read the DB directly:
`docker exec dev-postgres-1 psql -U xwiki -d xwiki -c "select xws_value from xwikistrings where xws_value like 'PPLTest.%'"`.

### 6b. Save succeeds with rights in the refactoring job thread

During the rename test (§4), confirm the updater's save call succeeds:
- In the rename **job log** (the UI job report — *not* `docker logs`, since the listener runs on the
  job thread), the summary must NOT be accompanied by `AccessDeniedException` /
  `NotGuestUserException` from `PagePropertyReferenceUpdater`.
- If save fails with a rights error: the Java component needs to wrap the save in a
  privileged execution context (e.g., `xwikiContext.setUserReference(superadmin)` before
  saving, then restore). File a bug in the extension if this occurs.

### 6c. `currentmixed` resolver round-trips stored Page values

A stored value like `PPLTest.TargetPage.WebHome` must resolve to a `DocumentReference` and then
serialize back **via the `local` serializer** identically — this is exactly the resolve/serialize
pairing the updater uses to compare against stored values. In the Groovy console (programming right):

```groovy
import org.xwiki.model.reference.DocumentReferenceResolver
import org.xwiki.model.reference.EntityReferenceSerializer

def resolver   = com.xpn.xwiki.web.Utils.getComponent(DocumentReferenceResolver.TYPE_STRING, "currentmixed")
def serializer = com.xpn.xwiki.web.Utils.getComponent(EntityReferenceSerializer.TYPE_STRING, "local")

def input = "PPLTest.TargetPage.WebHome"
def ref   = resolver.resolve(input)
def back  = serializer.serialize(ref)
println "Input:      ${input}"
println "Resolved:   ${ref}"      // full ref, e.g. xwiki:PPLTest.TargetPage.WebHome — EXPECTED
println "Serialized: ${back}"
println "Round-trips: ${back == input}"
```
(Use `TYPE_STRING`, not `.class` — the role is generic `DocumentReferenceResolver<String>`, and a
raw-class lookup can resolve the wrong component or `null`.)

**Expected:** `Round-trips: true`. The **Resolved** line showing the wiki prefix
(`xwiki:PPLTest.TargetPage.WebHome`) is **normal** — the resolver yields a fully-qualified reference
and the `local` serializer deliberately strips the wiki to match the stored form. Only the
**Serialized** value must equal the input. If `Round-trips` is `false`, review the resolver hint
(`currentmixed`) / serializer hint (`local`) used in `PagePropertyReferenceReader` and
`PagePropertyReferenceUpdater`.

---

## 7. Cleanup

```bash
# Stop and remove the dev compose stack (keeps volumes):
docker compose -f dev/docker-compose.yml down

# Wipe everything including data volumes:
docker compose -f dev/docker-compose.yml down -v
```

Remove test apps/pages created during verification: delete `PPLTest.*` space via
Administration > Delete Wiki Space (or manually delete each page).

---

## 8. Troubleshooting

| Symptom | Check |
|---------|-------|
| No backlinks appear | Indexer lag — wait a few seconds or edit+save the entry to re-index (full reindex: Admin → Search → Solr → Apply). Check log: `docker logs dev-xwiki-1 \| grep -i pagepropertylinks`. |
| Stored values not updated on rename | Check log for `PagePropertyReferenceUpdater`. Rights issue? HQL returning 0? |
| StringListProperty query returns 0 | Confirm `textValue` is the correct field name (§6a). |
| `links_extended` not populated | Confirm `FieldUtils.LINKS_EXTENDED = "links_extended"` (not `extendedLinks`) in the Solr schema. |
| Container fails to start after JAR deploy | Remove the JAR and restart: `docker exec ... rm /usr/local/tomcat/webapps/ROOT/WEB-INF/lib/page-property-links-*.jar && docker restart ...`. Check for classpath conflicts. |
| XWiki installer runs again | Data volume was wiped. Re-run installer or restore from backup. |
