# Vidbox TV

An Android TV app that browses films and series (catalogue from TMDB) and plays them through the
stream servers [vidbox.wtf](https://vidbox.wtf) uses. A companion web portal lives in `web/`.

Built the same way as «شبکه نمایش خانگی» in `alphadl-tv`: plain Java against the Android framework,
no libraries, and a command-line build (`javac` → `d8` → `aapt2` → `zipalign` → `apksigner`).

## Layout

```
scripts/build.sh            builds build/VidboxTV.apk
scripts/make-icons.swift    draws the TV home tile and the launcher icon
app/AndroidManifest.xml
app/res/                    banner.png (640x360), icon.png (192x192)
src/com/vidbox/tv/
  Vb.java                   catalogue (TMDB), stream servers, watch progress, watchlist
  VbUi.java                 the shared look: buttons, poster cards, focus, image loader
  VbBrowseActivity.java     Home rows; Movies, TV Shows, Genres, Watchlist, Search grids
  VbDetailActivity.java     one title: facts, buttons, episodes or similar films
  VbPlayerActivity.java     the player
  VbUpdate.java             self-update; VbUpdated.java reopens the app after one
.github/workflows/build-apk.yml   builds, signs and publishes every push to main
.github/scripts/progress.sh       the "build progress" status on the commit
web/                        companion web portal
```

## The screens

**Browse.** A tab bar (Home, Movies, TV Shows, Genres, Watchlist, Search). Home is a page of rows,
each a title and a line of cards: Continue watching, trending films and series, in cinemas now,
four streaming services, top rated. Each row loads on its own, so one slow list does not hold up
the page. The other tabs are a six-column poster grid that loads more as you go down. Genres has
a Films/Series switch followed by that kind's genres.

**Detail.** Poster, a line of facts, the overview, and the buttons: Play (or Resume from where you
stopped), Start over, Add to watchlist, Server. A series shows its seasons and a list of episodes,
each marked watched or with its resume time; Play picks up the episode you were on, or the next one
if you finished it. A film shows a row of similar films.

**Player.** See below.

### Focus and the remote

Focus looks the same everywhere: a button turns white with dark text; a poster card gets a white
ring, grows slightly, and its title turns white and scrolls if it is too long to fit. There is no
blur and no translucent layering, because the TV's graphics chip is weak.

- Up from the first row or the top of a grid reaches the tabs.
- Back from a page goes to its tab; Back on a tab goes to Home; Back on the Home tab leaves the app.
- In the player: Left/Right seek (faster while held), OK pauses and plays. Down opens the button
  row (previous episode, −10 s, play/pause, +10 s, next episode, speed, server); Up or Back leaves
  it; Back again leaves the player.

## How playing works

The stream servers are web pages with their own players, made for a mouse. Shown as they are on a
TV they sit paused and muted waiting for a click, the remote does nothing useful, and adverts open
over them. So the player opens the server's page out of sight in a WebView, taps the middle of it
(many wait for a click), and blocks advert hosts, pop-ups and any attempt to navigate away. Once
the page's `<video>` element has a film in it (longer than two minutes, so not an advert), only the
picture is shown, full-screen, and that element is driven from the app: play, pause, seek, speed,
and the position saved every five seconds for Resume.

### Choosing the server

Which servers work changes from hour to hour, so the list in `Vb.SERVERS` is not ranked by hand.
The app remembers for each server whether it last produced a film and how long it took
(`Vb.serverResult`), and tries them in that order (`Vb.serverOrder`): servers that have not been
failing first, quickest to start first. A server that fails is pushed back for six hours and then
gets another chance. If a page gives no video within 40 seconds, or the video stalls for 30, the
next server takes over from the same position. The Server button offers “Automatic” (the default)
or pinning one server to be tried first.

To see how each server does on a device, start a title with one server only and read the log:

```bash
adb shell am start -n com.vidbox.tv/.VbDetailActivity --el id 27205 --es type movie --ez play true --es probe 0
adb logcat -s Vidbox
```

`probe` is the server's index in `Vb.SERVERS`; the log says `PROBE 0 OK …` or `PROBE 0 FAIL …`.

## Build and install

Needs the Android SDK build-tools and platform, JDK 17, and `adb`.

```bash
scripts/build.sh
```

The result is `build/VidboxTV.apk`, version `dev`, signed with `keystore/debug.jks` (created on
the first build, not in git).

```bash
adb install -r -i com.vidbox.tv build/VidboxTV.apk
```

`-i com.vidbox.tv` records the app as its own installer, which is what lets it update itself
without a confirmation.

## Releases and self-update

Every push to `main` runs `.github/workflows/build-apk.yml`: it builds version `1.0.<run>`
(versionCode `1000 + run`), publishes the APK to the `latest` release, then commits `version.json`.
While it runs, the commit carries a “build progress” status with a percentage and the time left.

The build is signed with the key in the repository secrets `VB_KEYSTORE_BASE64`,
`VB_KEYSTORE_PASSWORD`, `VB_KEY_ALIAS` and `VB_KEY_PASSWORD`. Android only installs an update
signed with the same key as the copy it replaces, so without those secrets the workflow signs with
a throwaway key and warns that the build cannot update an installed app.

The app reads `version.json` from `main` each time its main screen comes to the front (at most
every three minutes). When it names a newer build the app downloads and installs it without
asking, and only while the main screen is in front, never during a film. The app closes for the
install and opens again. A local `dev` build never updates itself.

## Icon and TV tile

Same look as the owner's other TV apps: the name in white Helvetica Bold on a navy gradient over a
cyan bar. Redraw them with:

```bash
swift scripts/make-icons.swift
```

## Companion web portal

```bash
python3 web/server.py 8080
```

Then open <http://localhost:8080>.
