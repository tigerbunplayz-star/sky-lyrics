# Sky Lyrics

A Fabric mod for Minecraft **26.2** that shows the lyrics of whatever you're playing in Spotify as big, colourful 3D words popping up around you in the world, synced to the music.

- Reads the song straight from Windows' media controls: **no Spotify login, developer app or Premium needed**. Just have the Spotify desktop app playing.
- Synced lyrics come from [LRCLIB](https://lrclib.net), a free lyrics database, and, when LRCLIB has no synced version, from YouTube Music, which has synced lyrics for most songs (including lots of Hungarian ones). Accented songs work, even when a database spells them without accents.
- Songs that only have plain (untimed) lyrics, common for Hungarian music, still show: the timing is estimated, and you can line it up with the sync key.
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
| `;` | Sync: press the moment you hear a line start (fixes estimated timing for the current song) |
| `O` | Open the settings menu |

On a Hungarian keyboard these keys are `ő`, `ú` and `é`.

Rebind them in Options → Controls → Key Binds → Sky Lyrics.

## Settings

Press `O` in game for the settings menu: toggles, sliders for size, distance, height, spread, animation speed and fade timing, plus font choice. The **Colors** tab switches between **Original** (the colourful palettes, the default) and **Custom**, where you pick any colour with a colour picker and can fade the letters into a second colour. Changes show live on the words around you (sample words appear if nothing is playing). Save keeps them, Cancel or Esc undoes them. **Restart** saves, restarts the Spotify reader and downloads the current song's lyrics again: handy after switching which app to read, or if lyrics seem stuck. No game restart is ever needed.

Everything is also in `.minecraft/config/skylyrics.json` (created on first launch) if you prefer editing the file; restart the game after editing it.

| Setting | Default | What it does |
| --- | --- | --- |
| `font` | `"block"` | `"block"` chunky 3D letters, `"lobster"` fancy script, `"minecraft"` the game's font |
| `uppercase` | `true` | Write the lyrics in capitals |
| `size` | `1.0` | Text size multiplier |
| `depth` | `1.0` | 3D thickness of the letters (`0` = flat) |
| `colors` | 6 bright colours | Hex colours the word groups cycle through |
| `shimmer` | `true` | Gently shift colours over time |
| `colorMode` | `"original"` | `"original"` palette colours or `"custom"` |
| `customColor` / `customFadeColor` | `#4DD8FF` / `#9B7BFF` | Custom mode colours |
| `customFade` | `true` | Custom mode: letters fade from the main colour into the fade colour |
| `bob` | `true` | Words bob and sway a little |
| `spread` | `36` | How far left/right of your view words appear (degrees) |
| `distanceMin` / `distanceMax` | `24` / `36` | How far away words appear (blocks) |
| `heightMin` / `heightMax` | `-1` / `8` | Height above your eyes (blocks). Raise both (e.g. `18` / `30`) to put the lyrics up in the sky |
| `holdMs` | `2200` | How long a line stays after the next one starts before fading |
| `animationSpeed` | `1.0` | Speed of the pop-in, fade-out and floating |
| `maxWordsPerGroup` / `maxCharsPerGroup` | `3` / `12` | How lines are cut into groups |
| `offsetMs` | `0` | Timing shift (also set with `[` / `]`) |
| `player` | `"spotify"` | Which app to read; `"*"` uses whatever is playing (browsers, other players) |
| `estimateUnsyncedLyrics` | `true` | Show plain-only lyrics with estimated timing |
| `youtubeMusicLyrics` | `true` | Also look on YouTube Music when LRCLIB has no synced lyrics |
| `youtubeRegion` | `""` | Country for YouTube Music lyrics (licensed per country); empty = your system's country |

## Notes

- Windows 10/11 only (it uses the system media controls through a small PowerShell helper that the mod starts and stops automatically).
- If a song shows "no synced lyrics found", LRCLIB has no lyrics for it at all. Songs with only plain lyrics show with estimated timing. Synced lyrics can be contributed to lrclib.net.
- YouTube Music lyrics come through YouTube Music's unofficial app interface (the same approach as the open-source ytmusicapi). YouTube doesn't officially offer it, so it may stop working if they change it; LRCLIB keeps working either way. Turn it off with `youtubeMusicLyrics: false`.
- Fonts: Archivo Black and Lobster, both under the SIL Open Font License (see `licenses/`).

## Building

`gradlew build` (needs JDK 25). The jar ends up in `build/libs/`. GitHub Actions builds and tests every push.
