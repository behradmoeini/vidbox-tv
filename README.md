# Vidbox TV — Android TV Streaming App & Web Portal

An ultra-lightweight, high-performance Android TV app and companion web portal that streams movies and TV shows from [vidbox.wtf](https://vidbox.wtf).

Built following the standalone architecture of `namayesh` in `alphadl-tv`:
- **Plain Java (Android SDK only, zero third-party library dependencies)**
- **Blazing fast command-line build** (`javac` → `d8` → `aapt2` → `zipalign` → `apksigner` in ~1.5s)
- **Leanback Android TV UI**: 1080p/4K D-pad navigation, card scaling animations, dynamic blurred backdrop, TV remote controls
- **Multi-Server Streaming**: Seamlessly resolves and switches between Vidbox providers (Echo / VidLink, Max / YTHD, 4K, Vidfast, V2, Vidpro, Premium, Nxsha, 2Embed, Cinemaos, and Vidbox direct sessions)
- **Included Companion Web Portal (`web/`)**: Full-featured responsive web player and streaming portal

---

## Directory Structure

```
vidbox-tv/
├── README.md
├── scripts/
│   └── build.sh               # javac -> d8 -> aapt2 -> zipalign -> apksigner
├── app/
│   ├── AndroidManifest.xml    # Android TV & Leanback launcher manifest
│   └── res/
│       ├── drawable-xhdpi/
│       │   └── banner.png     # 320x180 Android TV Leanback banner
│       ├── mipmap-xxhdpi/
│       │   └── icon.png       # High-res app launcher icon
│       └── values/
│           ├── strings.xml
│           └── colors.xml
├── src/com/vidbox/tv/
│   ├── Vb.java                # Data layer: TMDB client, streaming server resolvers, cache, progress
│   ├── VbUi.java              # TV UI styling, focus animations, two-tier image loader with disk cache
│   ├── VbBrowseActivity.java  # Main TV screen: Home rows, Movies, TV Shows, Genres, Watchlist, Search
│   ├── VbDetailActivity.java  # Detail screen: Backdrop, synopsis, cast, server picker, season & episode list
│   ├── VbPlayerActivity.java  # Fullscreen TV player: Hardware-accelerated WebView, remote controls, ad-blocking
│   └── VbUpdate.java          # Self-update mechanism via remote release manifest
└── web/                       # Companion Web Streaming Portal
    ├── index.html             # Responsive streaming web interface
    ├── style.css              # Dark glassmorphism streaming theme
    ├── app.js                 # Catalog & streaming engine
    └── server.py              # Lightweight local HTTP server
```

---

## Features

### 1. Android TV Browse Experience (`VbBrowseActivity`)
- **Top Tab Bar**: `Home`, `Movies`, `TV Shows`, `Genres`, `Watchlist`, `Search`.
- **Home Rows**:
  - Continue Watching (with resume progress bar)
  - Trending Movies (`/trending/movie/day`)
  - Trending TV Shows (`/trending/tv/day`)
  - Now Playing in Theaters (`/movie/now_playing`)
  - Netflix Originals (`with_networks=213`)
  - Amazon Prime Shows (`with_networks=1024`)
  - Apple TV+ Shows (`with_networks=2552`)
  - Disney+ Shows (`with_networks=2739`)
  - Top Rated Movies & TV Shows
- **D-Pad Focus & Dynamic Backdrops**: Moving focus across cards smoothly updates the fullscreen background backdrop to the focused title.
- **Movies & Shows Grids**: 6-column poster grid with pagination and genre filtering chips.

### 2. Title Details (`VbDetailActivity`)
- Full synopsis, tagline, star rating, release year, runtime, HD badge, genre pills, and cast list.
- **Action Buttons**:
  - `[▶ Play / Resume]`: Primary action button, remembers exact resume positions.
  - `[+ Watchlist]`: Local persistence in SharedPreferences.
  - `[Server: Echo ▾]`: Dropdown dialog to select default streaming provider.
- **TV Series Episode Picker**: Horizontal season selector tabs + horizontal list of episode cards with episode thumbnails, titles, and air dates.

### 3. Fullscreen TV Player (`VbPlayerActivity`)
- Hardware-accelerated `WebView` engineered specifically for Android TV remote navigation.
- **Ad & Popup Blocking**: Custom `WebViewClient` blocks ad domains, popunders, and rogue redirects.
- **TV Remote Key Map**:
  - `DPAD_CENTER` / `OK` / `MEDIA_PLAY_PAUSE`: Play / Pause video.
  - `DPAD_LEFT`: Seek backward 10 seconds (with visual OSD feedback).
  - `DPAD_RIGHT`: Seek forward 10 seconds (with visual OSD feedback).
  - `DPAD_DOWN`: Brings up the on-screen control bar (Server switcher, Previous Episode, Next Episode, Reload).
  - `DPAD_UP`: Hides the control bar.
  - `BACK`: Hides control bar if open; otherwise saves watch progress and returns to details.
- **Auto-Save Progress**: Periodically records playback timestamps into local history.

### 4. Streaming Providers Supported
| Provider | Movie Embed | TV Embed | Notes |
|---|---|---|---|
| **Echo (VidLink)** | `https://vidlink.pro/movie/{id}` | `https://vidlink.pro/tv/{id}/{s}/{e}` | Primary server, customizable player |
| **Max (YTHD)** | `https://ythd.org/embed/{id}` | `https://ythd.org/embed/{id}/{s}-{e}` | High reliability |
| **4K (Vidrift)** | `https://embed.vidrift.net/embed/movie/{id}` | `https://embed.vidrift.net/embed/tv/{id}/{s}/{e}` | High bitrate streams |
| **Vidfast** | `https://vidfast.vc/movie/{id}` | `https://vidfast.vc/tv/{id}/{s}/{e}` | Fast initial buffer |
| **V2 (VAPlayer)** | `https://vaplayer.ru/embed/movie/{id}` | `https://vaplayer.ru/embed/tv/{id}/{s}/{e}` | Alternative CDN |
| **Vidpro (Vixsrc)** | `https://vixsrc.to/movie/{id}` | `https://vixsrc.to/tv/{id}/{s}/{e}` | Multi-audio sources |
| **Premium (Vidy)** | `https://www.vidy.st/movie/{id}` | `https://www.vidy.st/tv/{id}/{s}/{e}` | Direct player |
| **Nxsha** | `https://nxsha.space/embed/movie/{id}` | `https://nxsha.space/embed/tv/{id}/{s}/{e}` | Multi-language subtitles |
| **2Embed** | `https://2embed.stream/embed/movie/{id}` | `https://2embed.stream/embed/tv/{id}/{s}/{e}` | Backup mirror |
| **Cinemaos** | `https://cinemaos.tech/player/{id}` | `https://cinemaos.tech/player/{id}/{s}/{e}` | Fallback |

---

## Build & Installation

### Requirements
- Android SDK build-tools (for `aapt2`, `zipalign`, `apksigner`, `d8`)
- Java JDK 11+ (JDK 17 recommended)
- `adb` (for installation)

### Build the APK
```bash
scripts/build.sh
```
Output: `build/VidboxTV.apk` (Signed with local debug keystore in `keystore/`).

### Build and Sideload to Android TV / Chromecast
Connect your Android TV via adb (e.g. `adb connect 192.168.1.xxx:5555`):
```bash
scripts/build.sh --install
```

---

## Companion Web Portal

Run the companion website locally:
```bash
python3 web/server.py 8080
```
Open [http://localhost:8080](http://localhost:8080) in your browser.
