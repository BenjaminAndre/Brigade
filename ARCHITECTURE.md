# Architecture

Companion to `DESIGN_REQUIREMENTS.md`, which is the authority. This file records how the
requirements were translated into code, and the rules that keep them true.

## One Gradle module, package boundaries by rule

Brigade is a single `:app` module whose packages are laid out as the module graph it
*would* have. What makes code refactorable is the dependency graph, not the module
graph; at this size a four-module split costs four build files, four manifests and an
`api`/`implementation` decision on every new type, paid daily, for a benefit paid never.
Extraction later is `git mv` plus four build files — provided the direction below is
never violated.

```
content/       → kotlin + coroutines only.  NO android.*, NO Compose, NO presentation/
                 (exception: content/saf/** — the single Android-touching leaf)
presentation/  → content/ (ContentId, ContentPath) + coroutines.  NO android.*, NO Compose
journal/       → presentation/, content/ + coroutines.  NO android.*, NO Compose — the recap
render/        → presentation/, content/, Compose, Coil.  NO ui/, NO display/
display/       → render/, presentation/, android.*.       NO ui/
storage/       → adapters: android.* + the interfaces in content/, presentation/ and journal/
ui/            → everything
```

`ArchitectureTest` enforces this by reading the source. Forty lines of test in place of
four Gradle modules.

The rule that carries the most weight is the first. Keeping `content/` and
`presentation/` free of `android.*` is what makes the interesting logic unit-testable
here at all, with no Robolectric and no emulator. Three decisions exist mainly to
protect it:

- `ContentId` wraps a `String`, not an `android.net.Uri`;
- display selection is a pure function over a `DisplayInfo` data class, not over
  `android.view.Display`;
- `PresentationStore` holds no repository reference.

## One presentation state, two render targets

`PresentationState` is the single source of truth for what the players can see. It is
owned by `AppGraph` on the `Application` — **not** by a ViewModel.

That is not a style preference. The player window is an `android.app.Presentation`, a
`Dialog` on a secondary display with a lifecycle of its own that outlives Activity
recreation. State scoped to the Activity's `ViewModelStore` would be cleared underneath
it: the players' screen freezes on a stale frame while the GM's UI moves on. Worse, a
`viewModel()` call inside the player composition would silently construct a *second,
independent* state holder — literally the "two independent pieces of business logic"
§5 forbids, one innocent line away. `PlayerPresentation` therefore deliberately does
**not** set a `ViewTreeViewModelStoreOwner`, so that line fails loudly instead.

Both compositions collect the same `StateFlow` instance, and both draw the same
composable:

```
PresentationState (AppGraph, process-scoped)
        │
        ├──────────────► PlayerPreviewPane  ─┐
        │                                    ├─► PresentationSurface
        └──────────────► PlayerPresentation ─┘
```

`PresentationSurface` carries its invariants in its KDoc; they are what make the
identical-output claim a property rather than a convention.

### What the renderers actually draw

`PresentationState.frame()` projects state to a `Frame` — `Black`, `Picture` or `Info` —
and that projection is the *only* thing renderers consult. It replaced `effectiveVisual()`,
which returned a `VisualPresentation` and therefore could not express "black" distinctly
from "no image": a transition written against it cannot tell an intentionally blank screen
apart from an empty scene.

There is no separate blank control and no `blackout` flag. INFO *is* the blank control: a
campaign with no info configured projects to `Frame.Black`, which is the
"nothing specific, I'm preparing" state. One mode, one branch, one thing to test.

### Transitions

A transition is a function of two frames and time. `ActiveTransition` lives in the state and
carries `startNanos`, stamped once in the store; each window then computes progress as a
pure function of it while sampling its own frame clock. They are synchronised by
construction, with no shared animation object and no state ticking at 60 Hz — and a window
that attaches *mid*-transition, which HDMI hotplug does routinely, picks up the right
progress immediately.

Two rules that are not obvious and that a plausible implementation gets wrong:

- **Progress is passed as a lambda**, read only inside `graphicsLayer`/`drawWithContent`
  blocks. Read in composition instead, it recomposes both windows' render subtrees sixty
  times a second and re-invokes `AsyncImage` with a fresh modifier each frame.
- **Every layer paints its own bounds opaque black first.** Otherwise a dissolve between
  images of differing aspect leaves the incoming layer transparent in its letterbox bars,
  the outgoing image shows through them for the whole wash, and they snap to black the
  instant it ends — a visible pop at the end of every transition.

### One decode, two windows

Coil keys its memory cache by request size, and `AsyncImage` sizes its request to the
composable. Left alone, the preview and the player window build different requests, hold
different cache entries, and become ready at different moments — so a dissolve genuinely
shows different pixels in the two windows for its first frames. `PlayerImageModel` pins one
explicit size, built once in `AppGraph` and handed to both call sites through the `model`
parameter `PresentationSurface` already accepted. Grid and slot-bar thumbnails keep their
own small requests: they are GM-only, so §5 does not apply, and pinning them to the display
resolution would decode a battle map at full size for a 132 dp cell.

That size is **twice** the display's, not the display's own — `Viewport.MAX_ZOOM` — so the
pointer's deepest zoom still has a source pixel per screen pixel. About 33 MB per image at
1080p instead of 8, and nothing extra for images already smaller, since Coil never upsamples.

### The pointer is state, not a gesture

Pan and zoom live on `VisualPresentation` as a `Viewport` in fractions of the surface, carried
into `Frame.Picture` and applied by `PresentationSurface` as a `graphicsLayer` scale and
translation — the size read only to turn fractions into this window's pixels. Gestures exist
only in `PlayerPreviewPane`, which divides by its own size before calling the store.

Three things a plausible implementation gets wrong:

- **Reframing bypasses `change()`.** A pinch reports a step per input event; through
  `change()` each would bump the revision and stamp a dissolve of the map into itself.
  Transitions compare `frame().unframed()`, so presenting the same picture again resets the
  zoom without a wash either.
- **The store applies each step to the viewport it holds now**, inside its `update`. Computed
  in the UI from the last composed state, a step would silently drop whichever came before it
  whenever input outruns recomposition — which, during a pinch, is always.
- **The request is remembered per id** in `FrameLayer`. A pinch recomposes the layer at
  gesture rate, and rebuilding the `ImageRequest` each step would lean on Coil's request
  equality to avoid a reload.

The snapshot writer also sits behind `distinctUntilChanged()` on the snapshot, or a pinch
would be a disk write per finger movement for data it does not even persist.

| State | Owner | Lifetime |
|---|---|---|
| `PresentationStore`, `SlotBank`, `ContentRepository`, `PlayerDisplayHost` | `AppGraph` | process |
| `BrowsingState` | `GmViewModel` + `SavedStateHandle` | Activity — dying with the UI is correct |
| grid scroll, open dialog | `remember` / `rememberSaveable` | composition |

### Notes resolve before a Frame exists

A Markdown note becomes an image *before* `frame()` runs, so `Frame`, `PresentationSurface`
and both render targets needed no change at all to support notes — the players simply see a
picture.

The GM bar rides on `Scene.note`, and `frame()` does not consult it. That is why the bar
cannot leak onto the player display: `Frame` has no document field, so there is no
representation in which it could arrive. The guarantee is structural, not a convention.

The campaign date is deliberately **not** stored on `NoteBar`. It is campaign state, composed
at render time from the live config — carrying it would leave every already-resolved slot
showing the in-world date it happened to be resolved on.

Which image a note presents is decided in exactly one place, `ContentRepository.firstImage`.
Presenting a note and drawing its browser thumbnail both call it, so the grid shows precisely
the picture the players will get. Notes are read through `NoteSource`, an interface in
`content/` implemented by `saf/NoteReader`, which keeps that rule on the JVM side of the
Android boundary. Notes are never cached — they are edited in Obsidian mid-session — while
listings are.

Obsidian's `![[…]]` means "the file with this name, anywhere", not a path, so
`ContentRepository` keeps a lazily-built filename index over the whole tree, cleared by the
same `invalidate()` as the folder cache. Relative `![](…)` targets are resolved by pure string
arithmetic in `ContentPath.relativeTo` rather than by walking parents — which is both simpler
and testable on the JVM.

## Browsing and presentation cannot touch each other

§16 Trap 4 is structural here rather than aspirational. Tapping a thumbnail opens the
action menu; it has no path to presentation state at all. Only the slot bar and
*Afficher* present anything. The GM can browse the whole campaign mid-scene with no risk
of revealing something.

## Where 16:9 lives

Once: `PlayerDisplayStatus.DEFAULT_PLAYER_ASPECT`, used only when there is no display to
ask. The preview otherwise takes its ratio from the real attached display, so a 16:10
projector previews as 16:10 (§16 Trap 6).

## Where the state lives on disk

| What | Where | Why |
|---|---|---|
| Campaign root URI | app-private `SharedPreferences` | belongs to the install, not the campaign |
| Last presentation | app-private `SharedPreferences` | session scratch; restored on INFO |
| Recorder heartbeat | app-private `SharedPreferences` | overwritten every minute; only there to end a panel after a crash |
| **Slot bank** | `<campaign>/.brigade/slots.json` | points at campaign content, so it travels with it (§2.3) |
| **Session journal** | `<campaign>/.brigade/journal.tsv` | what the players saw; append-only, travels with the campaign |
| **Session recap** | `<campaign>/Brigade.md` | rendered from the journal for the GM to read in Obsidian (§25) |

Slots store **paths relative to the campaign root, never document URIs**. A document URI
is provider-specific and survives neither a folder move, a reinstall, nor the campaign
being opened on another device — which would defeat the reason for putting the file in
the campaign folder at all. The cost is that a path must be resolved against the tree at
load; the benefit is that a slot bank is as portable as the campaign, and readable in a
Git diff.

Nothing else is ever written to the campaign folder — and `Brigade.md` only when it carries
Brigade's `generated_by: Brigade` marker, or does not exist yet. A `Brigade.md` the GM wrote is
never touched.

### The session recap

The journal is derived from presentation state, not written at each call site:
`SessionRecorder` watches `PresentationState` and the display status, maps each state to a
`Panel` — note, image, INFO or black — and appends a line whenever that changes while a player
display is attached. Framing, the timer and transitions never change the panel, so a pinch at
gesture rate writes nothing. Slots, *Afficher* and INFO are recorded without knowing the
journal exists.

To name a note rather than the image it presents, the scene carries `Scene.origin`: the path
it was presented from. GM-side like `Scene.note`, and like it ignored by `frame()`.

Everything that decides what the file says — sessions split at gaps over six hours, sessions
under thirty minutes dropped, raw images filed under their note, the Markdown itself — lives in
`journal/`, which is plain Kotlin under the same rule as `presentation/`. `AppGraph` only reads
the journal, asks the repository which note presents each raw image (a reverse index built
once, and only when a raw image was shown), and writes the result. Rewrites wait three seconds
for the screen to settle, and are skipped when nothing changed, so Obsidian open beside
Brigade reloads the file once per change rather than on every tap.

## The escape hatch

`PlayerDisplayHost` is all of §16 Trap 10. Whether `Presentation` actually reaches HDMI
in a given Samsung DeX configuration is the largest unknown in the project. If it does
not, the fallback is a `PlayerActivity` launched with `ActivityOptions.setLaunchDisplayId`
rendering the same `PresentationSurface` against the same process-scoped state — a
different implementation of that one interface, and **nothing else changes**.

Related: `pickPlayerDisplay` excludes the display the GM UI is on rather than picking
"the non-default display". In standard DeX the external display *is* the desktop and the
GM Activity launches onto it, so "external" and "player" are not the same thing.

## Invariants worth not breaking

- No `<uses-permission>` in the manifest. SAF grants are not permissions and there is no
  network. The empty list is a feature.
- No user-facing string literal in Kotlin. Everything through `stringResource` (§21).
- The player surface renders no *application* text. Its blank state is pure black. The one
  exception is the campaign info panel, which is campaign content, not application text
  (§21.2) — and it is laid out at a fixed virtual resolution and uniformly scaled, so the
  preview and the player window break lines in identical places.
- `content/saf/DocumentTreeSource` is the only file that knows `DocumentsContract`
  exists, and every `ContentId` is a *document* URI built using the tree — never the raw
  tree URI, which has no document-id segment.
- The GM preview renders presentation state in every display state; a missing player
  display is an overlay on top of it, never a replacement (§5.1).
- The GM theme — Papier or Encre, following the tablet's dark mode — never reaches the
  player display. `render/` and `display/` may not import Material at all, which
  `ArchitectureTest` enforces, so there is no `MaterialTheme` for them to read.
