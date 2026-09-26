# Brigade

**B**idouille **R**apide d'**I**mages et **G**estion d'**A**tmosphère pour **D**euxième **E**cran

A small APK to help me run my tabletop games.

Brigade is a **GM-facing presentation console**, not a VTT. The physical tabletop stays the
game. Its one job:

> **The GM controls what the players see.**

The tablet is the GM surface; a wired HDMI display is the player surface. They are two
independent render targets of a single presentation state — not a mirror.

The interface is French. Its colours are measured from a Song-dynasty-style ink and
watercolour painting: **Papier** — xuan paper, ink text, sage and sienna — when the tablet is
in light mode, and **Encre** — the same palette on ink-black — in dark mode, for an evening
table. The player display is never themed.

## Demo

Tested with a chinese secondary monitor "UPERFECT" and my Samsung Galaxy Tab S7+ (with DisplayPort Alternative Mode, that's a requirement).
It worked flawlessly, even with the app in the background. Failed to take a screenshot of the secondary monitor, but it's exactly as the preview screen.
In the tiled mode, the left part is just Obsidian, reading the same file folders.


<img width="2800" height="1752" alt="Screenshot_20260922_194930_Obsidian" src="https://github.com/user-attachments/assets/f361b5b1-73af-440c-833f-b296282c5272" />
<img width="2800" height="1752" alt="Screenshot_20260922_194753_Gamehost" src="https://github.com/user-attachments/assets/025c63bb-f14e-41a9-b49b-dcb1eb889d96" />


https://github.com/user-attachments/assets/caa28161-16bc-4219-b9c4-7d64ca203d6f


## Features

**Campaign folder**
- Pick any folder through the Storage Access Framework; the grant survives restarts.
- Browse it as a tree — enter subfolders, or tap any breadcrumb to jump back, including to
  the root from any depth.
- Images and Markdown notes both appear and are both presentable.
- **Actualiser** re-reads the folder and `Campagne.md`.

**Six slots**
- Tap anything in the browser to *show it now* or park it in a slot.
- Tap a slot to put it on the player display, from anywhere in the tree.
- Long-press a slot to empty it; assigning over one overwrites it.
- Slots are stored as paths in the campaign folder, so they travel with the campaign and
  survive a reinstall.

**Player display**
- Fullscreen output to a wired HDMI display, with the tablet fully interactive beside it.
- Images are fitted, never distorted, and letterboxed in black.
- Keeps working when Brigade is backgrounded, and restores the scene when the cable comes
  back.
- Without a second display the app still works: the preview stays live under an «Aucun écran
  joueur» banner.

**Preview**
- A live view of the player surface, at the real display's aspect ratio.
- Shows the presentation state in every case — never a placeholder.

**Pointer**
- Pinch the preview to zoom, drag to pan — the player display follows. *Look here*, without
  pointing across the table.
- Double-tap for the whole picture again.
- Up to 2×, and genuinely sharp all the way: images are decoded at twice the display's
  resolution.
- Every slot comes up whole; INFO and back keeps the zoom. The preview shows `× 1,6` while
  zoomed so a corner is never mistaken for the whole map.

**INFO**
- Replaces the image with a full-screen campaign panel: title, location, in-world date in
  French, and the moon drawn in its true phase for that date.
- With no campaign info configured it shows black, so it doubles as the blank control —
  *nothing specific, I'm preparing*.

**Markdown notes**
- In the browser, each note shows the image recalling it would present — filled in behind
  the filenames as the folder opens.
- Recalling a note shows the players the first image it links that resolves.
- Obsidian `![[Jade-Fox.png]]` (found by filename anywhere in the campaign) and
  `![](../Portraits/Jade-Fox.png)` (relative to the note) both work.
- A GM-only bar above the preview shows date · name · element · faction. Nothing of it
  reaches the player display.
- A note with no image shows black and still fills the bar — useful for lore or secrets.

**Transitions**
- A **watercolor dissolve** between everything the player surface shows: a threshold sweeping
  a soft noise field, so the incoming image bleeds in rather than fading uniformly.
- `cut`, `fade` and `watercolor` available; the campaign picks one by name.

**SABLIER**
- An incense stick burning down the edge of the player display: 1, 2 or 5 minutes.
- No numbers for the players — the stick reads faster across a table. The GM sees the exact
  time on the button.
- «Encore 1 minute» lengthens it in place rather than resetting it.
- Keeps burning through scene changes, INFO and blanking.

**Session recap**
- `Brigade.md` at the campaign root: for each session, newest first, what was on the
  players' screen, in order, and for how long — then the same ranked by time on screen, the
  best proxy there is for what the session was really about.
- Read it in Obsidian: every note and image is a link, and every note shown gets a backlink.
- Recorded only while a player display is connected, so preparing at home never counts.
- A session is anything with no gap over **6 hours**, so evenings past midnight stay whole;
  anything under **30 minutes** is a test and is left out.
- A raw image shown by mistake counts under the note that presents it.
- Survives a crash: the last panel is closed at the last once-a-minute heartbeat.

**Deliberately absent** — no import, no database, no account, no network, no permissions, no
proprietary format. Delete Brigade and the campaign folder is still an ordinary folder.

## Campaign file

One optional file at the campaign root: **`Campagne.md`**. Everything Brigade reads lives in
YAML frontmatter under a `brigade:` key, so the file stays an ordinary note that Obsidian
renders with properties.

```markdown
---
brigade:
  transition: watercolor
  info:
    background: Fonds/parchemin.jpg
    show_lunar_state: true
    entries:
      - title: Le Renard de Jade
      - location: Auberge du Héron Noir
      - date: 1137-01-09
      - Saison: Printemps
---

# Notes de campagne

Tout ce qui suit l'en-tête est à toi. Brigade ne le lit pas.
```

- **`transition`** — `cut`, `fade` or `watercolor`. An unrecognised name falls back to `cut`
  so a typo is visible. No file at all means `watercolor`.
- **`info`** — what INFO shows. Entries appear in the order written; most are plain
  label/value rows, but `title` becomes the heading, `location` a headline, and `date` is
  written out in French with its weekday.
- **`show_lunar_state`** — draws the moon in its phase for `date`.
- **`background`** — an image behind the panel, relative to the campaign root.

Re-read when the folder is opened, when Brigade returns to the foreground, on **Actualiser**,
and on every INFO press. A missing or malformed file is normal and simply means the defaults
apply.

## Notes

```markdown
---
element: Métal
faction: Secte du Lotus
---

# Jade Fox

![[Jade-Fox.png]]

Le magistrat sait qu'elle ment.
```

- **Name** is the filename — rename a character by renaming the file.
- **`element`** — `Terre` 🪨 `Feu` 🔥 `Eau` 💧 `Métal` ⚔️ `Bois` 🌳, matched ignoring case and
  accents. An unrecognised value shows as text.
- **`faction`** — shown in its own colour.
- **Date** in the bar is the campaign's, from `Campagne.md`, not per-note.
- Keys are English; values are yours and are never translated.

## Dates before 1582 are Julian

`date:` follows the convention Wikipedia and historical lunar tables use: **Julian before
15 October 1582, Gregorian after.** Write the date exactly as your sources give it.

In the twelfth century the calendars differ by seven days, so a full moon listed as 9 January
1137 falls on 16 January in the proleptic Gregorian calendar `java.time` uses. Brigade
converts internally and displays what you wrote — the panel agrees with your notes and the
moon agrees with the sky.

The phase is a mean-synodic model, accurate to about a day.

## Your files stay yours

Campaign content is read where it lies and never copied, moved or modified. `Campagne.md` and
your notes are yours — Brigade reads them and never writes to them.

Brigade creates three files, and only these:

- `Brigade.md`, the session recap, once there has been a session worth listing. It is marked
  `generated_by: Brigade` and rewritten freely. A `Brigade.md` without that mark is yours:
  Brigade never overwrites it, and says so on the GM screen.
- `.brigade/journal.tsv`, what the players saw and when — the record `Brigade.md` is rebuilt
  from. One line per change.
- `.brigade/slots.json`, the six slots as paths relative to the campaign root.

All three are readable and Git-diffable. Commit them or ignore them.

## Building

Open in Android Studio (JDK 17, `compileSdk 35`, `minSdk 30`). The Gradle wrapper JAR is not
committed — Android Studio generates it on first sync, and CI provisions Gradle directly.

```bash
./gradlew test
```

```bash
./gradlew assembleDebug
```

## Installing on the tablet

Every push to `main` builds and publishes a debug APK to the rolling
[`latest`](https://github.com/BenjaminAndre/Brigade/releases/tag/latest) prerelease. The URL
is stable, so it can be bookmarked on the tablet: open it, download `app-debug.apk`, install.
You may need to allow installs from unknown sources for your browser.

## Development without the hardware

Most of the interesting logic is pure Kotlin and runs under `./gradlew test` with no device.
For the player display itself, **Settings → Developer options → Simulate secondary displays**
creates a real display with `FLAG_PRESENTATION` that the production code path drives
unmodified — no second code path, nothing to drift.

## Documents

- `CHANGELOG.md` — what changed, and when.
- `DESIGN_REQUIREMENTS.md` — what Brigade is, and the ten traps it must not fall into.
- `ARCHITECTURE.md` — how that was translated into code, and the rules that hold it.
