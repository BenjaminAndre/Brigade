# Changelog

Brigade is versioned `major.minor.patch`. The patch bumps on anything that ships; gaps are
normal. `versionCode` is derived as `major * 10000 + minor * 100 + patch`.

Entries before 0.4.1 are reconstructed from the commit history and the design notes, and are
coarser than what follows.

---

## 0.6.0 — Récap de séance

A record for the GM: `Brigade.md` at the campaign root lists, per session, what the players
had on screen, in order, and for how long. Time on screen is a good proxy for what a session
was really about.

- **Per session, newest first**: the date and hours, a timeline of every panel with its time
  on screen, then the same ranked by total. INFO and black appear in the timeline but not the
  ranking — they are you preparing or pausing.
- **A session** is everything with no gap over 6 hours, so an evening past midnight stays one
  session. Under 30 minutes is a test and is not listed.
- **Notes over raw images**: an image shown on its own is filed under the note that presents
  it, even if that note was never shown. A raw portrait followed by its own note is one entry.
- **Recorded only while a player display is connected**, so recalling slots at home is not
  a session. Unplugged time never counts.
- Every note and image is an Obsidian link by full path, so the recap is a way back into the
  campaign, and each note shown gets a backlink.
- Rewritten a few seconds after the screen settles, so Obsidian open beside Brigade updates
  as you play.

**Where it lives**

- `.brigade/journal.tsv` is the record: one line per change, append-only, UTC to the second.
  `Brigade.md` is rendered from it and can always be rebuilt.
- `Brigade.md` is created only once there is a session worth listing, and is hidden from the
  browser. It carries `generated_by: Brigade`; a `Brigade.md` without it is yours, is never
  overwritten, and the GM screen says so.
- A panel left open by a Brigade killed mid-session is ended at the last once-a-minute
  heartbeat on the next launch — at worst a minute lost, not the panel.

**Also**

- The last presentation remembers which note or image it came from, so a relaunch mid-session
  still names what is on screen.

## 0.5.1 — Papier et encre

The GM interface re-coloured to sit with the campaign's art, a Song-dynasty-style ink and
watercolour painting, instead of a generic cool dark blue.

- **Colours measured from the painting**, not picked by eye. It is far more muted than it
  looks: the paper is the only thing with real saturation, the blue-green mountains are warm
  greys, and the one warm accent — a lantern tassel — is burnt sienna. So the accents are
  sage and sienna, and the blue is gone.
- **Two schemes, following the tablet's dark mode**, switched from Android's quick settings:
  **Papier** (paper ground, ink text) to prepare in daylight, **Encre** (ink ground, paper
  text) for an evening table, where a bright tablet dazzles the GM.
- Titles and the folder path in the system serif. No font is bundled or downloaded.
- Captions over thumbnails and the preview's zoom badge in warm ink and paper white rather
  than black and white.
- The player display is untouched in both modes, and now provably so: the renderer is barred
  from importing Material at all.

**Fixes**

- The idle `INFO` and `SABLIER` buttons were Material's default purple-grey: the old theme
  never set the colour they read. Every role is set now, in both schemes.
- The window shown before the first frame matches the mode, so a cold start in light mode
  no longer flashes dark.
- Switching the tablet between light and dark while Brigade is open now also switches the
  status-bar icons, which would otherwise stay in the old mode — invisible against the new
  background.

## 0.5.0 — Pointer

The two things the first real session asked for.

**Pointer.** Pinch the preview to zoom, drag to pan, double-tap for the whole picture. The
player display follows, so the GM can say *look here* without leaning across the table.

- Up to 2×. Images are now decoded at twice the player display's resolution, so every zoom
  up to the cap shows real detail rather than an enlarged bitmap. Costs about 33 MB per image
  at 1080p instead of 8; images already smaller than that cost nothing extra.
- Presenting a picture always starts it whole — a slot always means the same thing.
  Re-tapping the live slot snaps back exactly like a double-tap. INFO and back keeps the zoom.
- Reframing never dissolves: the watercolor is for changing picture, not for moving around
  one.
- Gestures only work over a picture; on INFO or black there is nothing to frame.
- The preview shows the zoom factor while zoomed in.
- Not restored after a relaunch.

**Note thumbnails.** Notes in the browser now show the image recalling them would present,
instead of a bare filename. A folder of notes had been a wall of names.

- Resolved by the same code that presents the note, so the thumbnail is exactly the picture
  the players will get.
- Only the open folder's notes are read, after the grid is already on screen, top first.
  Leaving the folder stops the reading.
- Re-read each time the folder is opened and on *Actualiser*, so a note edited in Obsidian
  shows its new image without restarting anything. A newly *added* image file still needs
  *Actualiser* first, exactly as presenting it does.

**Fixes**

- A note whose first resolving embed was another note or a PDF presented black. Only images
  count now; the rest are skipped like broken links.
- The last presentation was written to disk on every state change, including ones that
  changed nothing it stores. It is now written only when it actually differs.
- Leaving a folder while it was still listing could briefly show its contents under the next
  folder's name.

## 0.4.3

- Corrected the repository name to its canonical `Brigade`. The lowercase URL worked through
  a GitHub redirect, which warned on every push.

## 0.4.2

- The GitHub repository was renamed. Updated the release link in `README.md` and pointed the
  git remote at the new URL.

## 0.4.1

Documentation only — no behaviour change.

- Rewrote `README.md` as a feature reference rather than a running history.
- Added this changelog.
- Removed `PLAN_V0.2.md` and `PLAN_V0.3.md`; their content lives here and in
  `DESIGN_REQUIREMENTS.md`.
- Started versioning properly. Previous releases all shipped as `0.1.0`.

## 0.4.0 — Brigade

Renamed, and given the one control a session proved it needed.

**Renamed from Gamehost to Brigade** — *Bidouille Rapide d'Images et Gestion d'Atmosphère
pour Deuxième Ecran*.

- New package `com.brigade` and a new `applicationId`, so it installs alongside the old app
  rather than upgrading it. The campaign folder must be re-picked once.
- The slot bank moved from `.gamehost/slots.json` to `.brigade/slots.json`, and the campaign
  frontmatter key from `gamehost:` to `brigade:`. Neither migrates — existing slots are
  re-assigned once.
- New launcher icon, cropped and fitted to an adaptive icon at every density.

**Incense timer.** A stick burning down the edge of the player display, so the table can see
how long is left without being told.

- `SABLIER` beside `INFO`: light 1, 2 or 5 minutes, add a minute, or put it out.
- No numerals for the players — a stick reads faster across a table and keeps the player
  surface free of application text. The GM gets the exact time on the button.
- Extending lengthens the stick in place rather than resetting it, so the players read it as
  mercy. Extending one that already went out lights a fresh minute.
- Burns on regardless of scene changes, INFO, or blanking — it is an overlay, not part of the
  picture. Never restored after a relaunch.

**`NOIR` removed.** `INFO` now doubles as the blank control: a campaign with no info
configured shows black, which is the *nothing specific, I'm preparing* state a separate
button was serving. One control, one meaning, and `blackout` is gone from the state entirely.

**Fixes**

- The preview could overflow its pane in wide-but-short windows and paint over the slot bar
  and the browser. `fillMaxWidth().aspectRatio()` fixes the width, so when the derived height
  exceeded the space available nothing satisfied the constraints and the modifier fell
  through to an unbounded size. The preview is now fitted against both dimensions explicitly.
- `Campagne.md` is re-read when Brigade returns to the foreground and when *Actualiser* is
  pressed, not only on INFO — so editing it in Obsidian beside Brigade needs no reload.

## 0.3.0 — Markdown notes

Notes became presentable content alongside images.

- Notes appear in the browser and go into slots like anything else.
- Recalling a note shows the players the first image it links **that resolves**, so a broken
  link or a web URL is skipped rather than blanking the display.
- Both link syntaxes: Obsidian's `![[Jade-Fox.png]]`, resolved by filename anywhere in the
  campaign, and `![](../Portraits/Jade-Fox.png)`, resolved relative to the note.
- A GM-only bar above the preview: campaign date, character name, element as an emoji,
  faction. Nothing of it reaches the player display.
- A note linking no image shows black and still fills the bar — the useful case for a lore or
  secrets note.

## 0.2.0 — Transitions and campaign info

- **Watercolor dissolve** between everything the player surface shows, driven by an animated
  threshold over a noise field rather than a uniform fade. `cut` and `fade` also available;
  a campaign picks one by name in `Campagne.md`.
- **`INFO`** — a full-screen campaign panel replacing the image: title, location, in-world
  date written out in French, and the moon drawn in its true phase for that date.
- **`Campagne.md`** — one optional file at the campaign root, read for configuration and
  panel content, never written to.
- Dates before 15 October 1582 are read as **Julian**, matching Wikipedia and historical lunar
  tables. In the twelfth century that is a seven-day difference, and getting it wrong would
  silently misdate both the panel and the moon.

## 0.1.0 — Select → preview → show

The first working version.

- Pick a campaign folder through the Storage Access Framework; the grant persists.
- Browse it as a tree, with a breadcrumb back to the campaign root from any depth.
- Six slots holding images by campaign-relative path, saved in the campaign folder.
- A live preview of the player surface on the tablet, at the real display's aspect ratio.
- Fullscreen output to a wired HDMI display via Android's `Presentation` API, with the tablet
  remaining fully interactive.
