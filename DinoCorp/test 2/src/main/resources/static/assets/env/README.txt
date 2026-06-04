LOST IN THE WOODS — ENVIRONMENT ANSI BACKGROUNDS
================================================

Drop a plain-text ANSI/ASCII art file here and it becomes that location's
background automatically (rendered green-on-black with CRT glow). Zero tokens,
no API — pure cached text, exactly per the engine spec.

HOW TO PROVIDE EACH ONE
-----------------------
- One .txt file per location, named EXACTLY (lowercase):
    dense_forest.txt
    clearing.txt
    swamp.txt
    cliff.txt
    stream.txt
    pond.txt
    cave.txt
- Plain UTF-8 text. Just the art — no quotes, no JSON, no code.

CANVAS / GRID SETTINGS (what to author to)
------------------------------------------
- Width:  up to ~110 characters per line (it is clipped if wider). 80 is a safe target.
- Height: up to ~20 lines (the panel is ~200px tall; extra lines are clipped).
- Use a FIXED line width — pad shorter lines with spaces so columns line up.
- Character palette (light -> dark): space  .  :  -  =  +  *  #  and the
  block ramp  ░ ▒ ▓ █   plus line glyphs  | / \ _ - = ^ ~  for trees/mist/etc.
- Monospace assumed. Color is applied by the engine (green) — author in plain text,
  do NOT add ANSI color escape codes.

STYLE
-----
- 21st-century isolating night forest, matching the in-game mood of each location
  (dense_forest = wall of pines; swamp = reeds + water line; cliff = rock face + drop;
   stream/pond = water; cave = enclosing rock arch; clearing = open with a moon).
- It frames the story text, so keep it readable and not too busy in the center.

If a file is missing, that location uses the built-in geometric SVG instead.
Send me one sample and I'll confirm it lines up; then you can do the rest.
