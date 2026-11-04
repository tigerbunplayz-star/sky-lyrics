# Sky Lyrics

A Fabric mod for Minecraft **26.2** that shows the lyrics of whatever you're playing in Spotify, synced line by line, as giant glowing text floating in the sky.

- Reads the song straight from Windows' media controls — **no Spotify login, developer app or Premium needed**. Just have the Spotify desktop app playing.
- Synced lyrics come from [LRCLIB](https://lrclib.net), a free lyrics database.
- Lines fade and slide in, light up left-to-right as they're sung (karaoke), with the next line shown faded underneath.
- The text drifts around to stay in front of you, high above the horizon, and glows at night.
- Client-side only: works in singleplayer and on any server.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) (for 26.2) and `sky-lyrics-x.y.z.jar` in your `.minecraft/mods` folder.
3. Start Minecraft with the Fabric profile, play a song in Spotify, and look up.

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
| `distance` | `48` | How far away the text floats (blocks). Lower it with very small render distances. |
| `size` | `1.0` | Text size multiplier |
| `elevation` | `28` | Degrees above the horizon |
| `followView` | `true` | Text swings around to stay in front of you; `false` keeps it fixed at `fixedYaw` |
| `followSpeed` | `1.6` | How fast it catches up when you turn |
| `fixedYaw` | `180` | Direction when not following: 0 south, 90 west, 180 north, 270 east |
| `showNextLine` | `true` | Show the upcoming line, faded |
| `karaoke` | `true` | Light up each line as it's sung |
| `lyricColor` / `unsungColor` / `titleColor` / `dimColor` | | Hex colours |
| `opacity` | `1.0` | Overall transparency |
| `shadow` / `background` | `true` / `false` | Text shadow, dark box behind text |
| `offsetMs` | `0` | Timing shift (also set with `[` / `]`) |
| `player` | `"spotify"` | Which app to read; `"*"` uses whatever is playing (browsers, other players) |
| `maxLineWidth` | `150` | Wrap width in font pixels |

## Notes

- Windows 10/11 only (it uses the system media controls through a small PowerShell helper that the mod starts and stops automatically).
- If a song shows "no synced lyrics found", LRCLIB doesn't have timed lyrics for it yet — you can add them at lrclib.net.

## Building

`gradlew build` (needs JDK 25). The jar ends up in `build/libs/`. GitHub Actions builds every push.
