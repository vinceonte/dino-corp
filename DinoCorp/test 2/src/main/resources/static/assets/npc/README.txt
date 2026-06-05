LOST IN THE WOODS — ANTAGONIST (7 SINS) ON-SCENE SPRITES
========================================================

These PNGs are generated from the ANSI art under  ../../../../../../Antagonist/
by  tools/antagonist2png.mjs  and shown in the on-scene NPC panel by renderNpc()
in lost_in_woods.html. Sin keys: adam, banner, felicia, nagi, sammuel, vincent, yuri.

THREE ASSET TIERS (and exactly when each is used)
-------------------------------------------------
1) BASE FORM           <id>.png
   Default sprite shown whenever that sin is present in a scene
   (npc = the sin's key, outcome = "continue") and no stance/ending applies.

2) EMOTION & STANCE    <id>_<stance>.png
   Chosen DYNAMICALLY per scene by matching the narrative text to keywords,
   instantly and locally (no API call). Priority + keywords live in NPC_STANCES
   in lost_in_woods.html. Every sin has an "attacking" stance for combat beats.
   Available stances:
     adam:    attacking, spiteful, pushing, grinning, relaxed
     banner:  attacking, rage, resentment, grinning
     felicia: attacking, crying, sad, kind
     nagi:    attacking, drowsy, dragging, fullvoid
     sammuel: attacking, gorging, hoarding, ecstacy, hollow
     vincent: attacking, predator, possessive, seductive, rapturous
     yuri:    attacking, sneering, covetous, resentful, brooding

3) ENDING FORM         <id>_demon.png   (RESTRICTED)
   Only used when the run hits a late-game ending event —
   outcome in { escape, transformation, lost, secret }. Never on a normal
   "continue" beat. (Deliberately NOT shown on a plain "death" ending.)

If a sprite is missing, that sin falls back to the built-in green ANSI portrait.
Regenerate all: node tools/antagonist2png.mjs Antagonist src/main/resources/static/assets/npc
