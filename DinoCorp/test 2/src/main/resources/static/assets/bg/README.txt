LOST IN THE WOODS — BACKGROUND ART DROP-IN
==========================================

Drop location background images in THIS folder. The game auto-detects them and
uses them in place of the geometric SVG fallback — no code change needed.

REQUIRED FILENAMES (lowercase, exact) — one per location the AI can report:
  dense_forest.png
  clearing.png
  swamp.png
  cliff.png
  stream.png
  pond.png
  cave.png
  yawning_cave.png   (optional cave VARIANT — see "variants" below)

FORMAT:
  - .png (the loader fetches assets/bg/<location>.png).
  - Wide landscape (the current art is 320 x 90, cover-cropped, center-anchored into the 200px scene panel).
  - File size: keep each well under ~400 KB so beats load fast. (These also preload on page load for instant swaps.)

VARIANTS (auto-selected, instant, no API):
  - A location may have more than one piece of art. The engine picks the best fit from
    the scene's narrative text via a keyword match in ENV_VARIANTS (see lost_in_woods.html).
  - Shipped example — "cave": cave.png is used by default; yawning_cave.png is chosen when the
    scene reads like a cave MOUTH/entrance (mouth, entrance, opening, maw, archway, gaping...).
  - Add more variants by extending ENV_VARIANTS and dropping the matching <variant>.png here.

STYLE (per Character_Style.md + the survival-horror reference):
  - Dark, gloomy, pre-rendered survival-horror mood (think classic Resident Evil / Alone in the Dark pre-rendered rooms).
  - Heavy shadow, low-key lighting, desaturated, a single cold or sickly light source.
  - Each file matches its location: dense_forest (claustrophobic trees), clearing (open, exposed),
    swamp (murky water, fog), cliff (drop/height), stream, pond (still reflective water), cave (black, enclosed).

If a file is missing, that scene simply uses the built-in geometric SVG until you add it.
