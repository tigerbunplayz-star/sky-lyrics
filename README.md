# Sky Lyrics

A Fabric mod for Minecraft **26.2** that shows the lyrics of whatever you're playing in Spotify as big, colourful 3D words popping up around you in the world, synced to the music.

- Reads the song straight from Windows' media controls: **no Spotify login, developer app or Premium needed**. Just have the Spotify desktop app playing.
- Synced lyrics come from [LRCLIB](https://lrclib.net), a free lyrics database. Hungarian and other accented songs work, including when the database spells them without accents.
- Each line is cut into short word groups that pop up as they're sung, scattered left, right, near and far, in chunky 3D block letters.
- Every group gets its own bright colour with a soft gradient and shimmer, and the words gently bob and sway.
- Walk around the words to see their 3D sides. They glow at night.
- Client-side only: works in singleplayer and on any server.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2 (0.19 or newer).
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) (for 26.2) and `sky-lyrics-x.y.z.jar` in your `.minecraft/mods` folder.
3. Start Minecraft with the Fabric profile and play a song in Spotify.

## Controls

| Key | Action |
| --- | --- |
| `K` | Show / hide the lyrics |
| `[` | Lyrics timing earlier (+100 ms) |
| `]` | Lyrics timing later (−100 ms) |

Rebind them in Options → Controls → Key Binds → Sky Lyrics.

## Settings

Edit `.minecraft/config/skylyrics.json` (created on first launch), then restart the game.

| Setting | Default | What it does |
| --- | --- | --- |
| `font` | `"block"` | `"block"` chunky 3D letters, `"lobster"` fancy script, `"minecraft"` the game's font |
| `uppercase` | `true` | Write the lyrics in capitals |
| `size` | `1.0` | Text size multiplier |
| `depth` | `1.0` | 3D thickness of the letters (`0` = flat) |
| `colors` | 6 bright colours | Hex colours the word groups cycle through |
| `shimmer` | `true` | Gently shift colours over time |
| `bob` | `true` | Words bob and sway a little |
| `spread` | `38` | How far left/right of your view words appear (degrees) |
| `distanceMin` / `distanceMax` | `13` / `22` | How far away words appear (blocks) |
| `heightMin` / `heightMax` | `-0.5` / `4.5` | Height above your eyes (blocks). Raise both (e.g. `12` / `20`) to put the lyrics up in the sky |
| `holdMs` | `2200` | How long a line stays after the next one starts |
| `maxWordsPerGroup` / `maxCharsPerGroup` | `3` / `12` | How lines are cut into groups |
| `offsetMs` | `0` | Timing shift (also set with `[` / `]`) |
| `player` | `"spotify"` | Which app to read; `"*"` uses whatever is playing (browsers, other players) |

## Notes

- Windows 10/11 only (it uses the system media controls through a small PowerShell helper that the mod starts and stops automatically).
- If a song shows "no synced lyrics found", LRCLIB doesn't have timed lyrics for it yet. You can add them at lrclib.net.
- Fonts: Archivo Black and Lobster, both under the SIL Open Font License (see `licenses/`).

## Building

`gradlew build` (needs JDK 25). The jar ends up in `build/libs/`. GitHub Actions builds and tests every push.
