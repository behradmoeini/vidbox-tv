/**
 * Vidbox Web — Companion Streaming Portal
 * Connects directly to TMDB & Vidbox servers to stream movies and TV shows.
 */

const TMDB_KEY = 'ef311eb0b9b07b9c73e9fb0a732cc150';
const TMDB_BASE = 'https://api.themoviedb.org/3';
const IMG_POSTER = 'https://image.tmdb.org/t/p/w500';
const IMG_BACKDROP = 'https://image.tmdb.org/t/p/w1280';

// Vidbox Streaming Servers
const SERVERS = [
  { name: 'Echo (VidLink)', key: 'echo', movie: 'https://vidlink.pro/movie/{id}?autoplay=true', tv: 'https://vidlink.pro/tv/{id}/{s}/{e}?autoplay=true' },
  { name: 'Max (YTHD)', key: 'max', movie: 'https://ythd.org/embed/{id}', tv: 'https://ythd.org/embed/{id}/{s}-{e}' },
  { name: '4K (Vidrift)', key: '4k', movie: 'https://embed.vidrift.net/embed/movie/{id}?autoplay=true', tv: 'https://embed.vidrift.net/embed/tv/{id}/{s}/{e}?autoplay=true' },
  { name: 'Vidfast', key: 'vidfast', movie: 'https://vidfast.vc/movie/{id}?autoplay=true', tv: 'https://vidfast.vc/tv/{id}/{s}/{e}?autoplay=true' },
  { name: 'V2 (VAPlayer)', key: 'v2', movie: 'https://vaplayer.ru/embed/movie/{id}?autoplay=true', tv: 'https://vaplayer.ru/embed/tv/{id}/{s}/{e}?autoplay=true' },
  { name: 'Vidpro (Vixsrc)', key: 'vidpro', movie: 'https://vixsrc.to/movie/{id}', tv: 'https://vixsrc.to/tv/{id}/{s}/{e}' },
  { name: 'Premium (Vidy)', key: 'premium', movie: 'https://www.vidy.st/movie/{id}?color=38BDF8&autoplay=true', tv: 'https://www.vidy.st/tv/{id}/{s}/{e}?color=38BDF8&autoplay=true' },
  { name: 'Nxsha', key: 'nxsha', movie: 'https://nxsha.space/embed/movie/{id}?lang=en&autoplay=true&sub=en', tv: 'https://nxsha.space/embed/tv/{id}/{s}/{e}?lang=en&autoplay=true&sub=en' },
  { name: '2Embed', key: '2embed', movie: 'https://2embed.stream/embed/movie/{id}', tv: 'https://www.2embed.stream/embed/tv/{id}/{s}/{e}' },
  { name: 'Cinemaos', key: 'cinemaos', movie: 'https://cinemaos.tech/player/{id}', tv: 'https://cinemaos.tech/player/{id}/{s}/{e}' }
];

let currentMedia = null;
let currentSeason = 1;
let currentEpisode = 1;
let currentServer = 'echo';

// State
let featuredItem = null;

// DOM Elements
const heroSection = document.getElementById('heroSection');
const heroBackdrop = document.getElementById('heroBackdrop');
const heroTitle = document.getElementById('heroTitle');
const heroRating = document.getElementById('heroRating');
const heroYear = document.getElementById('heroYear');
const heroType = document.getElementById('heroType');
const heroOverview = document.getElementById('heroOverview');
const heroPlayBtn = document.getElementById('heroPlayBtn');
const heroWatchlistBtn = document.getElementById('heroWatchlistBtn');
const mainContainer = document.getElementById('mainContainer');

const playerModal = document.getElementById('playerModal');
const playerTitle = document.getElementById('playerTitle');
const playerIframe = document.getElementById('playerIframe');
const closePlayerBtn = document.getElementById('closePlayerBtn');
const serverSelect = document.getElementById('serverSelect');
const episodeBar = document.getElementById('episodeBar');
const seasonSelect = document.getElementById('seasonSelect');
const episodeSelect = document.getElementById('episodeSelect');
const prevEpBtn = document.getElementById('prevEpBtn');
const nextEpBtn = document.getElementById('nextEpBtn');

const navBtns = document.querySelectorAll('.nav-btn');
const searchInput = document.getElementById('searchInput');
const searchBtn = document.getElementById('searchBtn');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
  initNav();
  initPlayerControls();
  loadHome();
});

// Navigation Tabs
function initNav() {
  navBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      navBtns.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const tab = btn.dataset.tab;
      if (tab === 'home') {
        heroSection.style.display = 'flex';
        loadHome();
      } else if (tab === 'movies') {
        heroSection.style.display = 'none';
        loadGrid('movie', `${TMDB_BASE}/discover/movie?api_key=${TMDB_KEY}&sort_by=popularity.desc`);
      } else if (tab === 'tv') {
        heroSection.style.display = 'none';
        loadGrid('tv', `${TMDB_BASE}/discover/tv?api_key=${TMDB_KEY}&sort_by=popularity.desc`);
      } else if (tab === 'watchlist') {
        heroSection.style.display = 'none';
        loadWatchlist();
      }
    });
  });

  searchBtn.addEventListener('click', performSearch);
  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') performSearch();
  });
}

function performSearch() {
  const q = searchInput.value.trim();
  if (!q) return;
  heroSection.style.display = 'none';
  loadGrid('search', `${TMDB_BASE}/search/multi?api_key=${TMDB_KEY}&query=${encodeURIComponent(q)}`);
}

// Home Rows Loader
async function loadHome() {
  mainContainer.innerHTML = '<div style="text-align:center; padding: 40px; color: #94A3B8;">Loading catalog...</div>';

  try {
    const [trendingMovies, trendingTv, nowPlaying, netflix, prime, apple, disney, topRated] = await Promise.all([
      fetchJson(`${TMDB_BASE}/trending/movie/day?api_key=${TMDB_KEY}`),
      fetchJson(`${TMDB_BASE}/trending/tv/day?api_key=${TMDB_KEY}`),
      fetchJson(`${TMDB_BASE}/movie/now_playing?api_key=${TMDB_KEY}`),
      fetchJson(`${TMDB_BASE}/discover/tv?api_key=${TMDB_KEY}&with_networks=213&sort_by=popularity.desc`),
      fetchJson(`${TMDB_BASE}/discover/tv?api_key=${TMDB_KEY}&with_networks=1024&sort_by=popularity.desc`),
      fetchJson(`${TMDB_BASE}/discover/tv?api_key=${TMDB_KEY}&with_networks=2552&sort_by=popularity.desc`),
      fetchJson(`${TMDB_BASE}/discover/tv?api_key=${TMDB_KEY}&with_networks=2739&sort_by=popularity.desc`),
      fetchJson(`${TMDB_BASE}/movie/top_rated?api_key=${TMDB_KEY}`)
    ]);

    // Setup Spotlight Hero from #1 trending movie
    if (trendingMovies.results && trendingMovies.results.length > 0) {
      setHero(trendingMovies.results[0], 'movie');
    }

    mainContainer.innerHTML = '';

    // Check Continue Watching
    const history = getHistory();
    if (history.length > 0) {
      renderRow('Continue Watching', history);
    }

    renderRow('Trending Movies', (trendingMovies.results || []).map(i => ({ ...i, media_type: 'movie' })));
    renderRow('Trending TV Shows', (trendingTv.results || []).map(i => ({ ...i, media_type: 'tv' })));
    renderRow('Now Playing in Theaters', (nowPlaying.results || []).map(i => ({ ...i, media_type: 'movie' })));
    renderRow('Netflix Originals', (netflix.results || []).map(i => ({ ...i, media_type: 'tv' })));
    renderRow('Amazon Prime Shows', (prime.results || []).map(i => ({ ...i, media_type: 'tv' })));
    renderRow('Apple TV+ Shows', (apple.results || []).map(i => ({ ...i, media_type: 'tv' })));
    renderRow('Disney+ Shows', (disney.results || []).map(i => ({ ...i, media_type: 'tv' })));
    renderRow('Top Rated Movies', (topRated.results || []).map(i => ({ ...i, media_type: 'movie' })));
  } catch (err) {
    mainContainer.innerHTML = `<div style="text-align:center; padding: 40px; color: #EF4444;">Failed to load rows: ${err.message}</div>`;
  }
}

function setHero(item, type) {
  featuredItem = { ...item, media_type: type };
  heroTitle.textContent = item.title || item.name;
  heroRating.textContent = `★ ${(item.vote_average || 0).toFixed(1)}`;
  heroYear.textContent = (item.release_date || item.first_air_date || '').substring(0, 4);
  heroType.textContent = type === 'tv' ? 'TV Show' : 'Movie';
  heroOverview.textContent = item.overview || '';
  if (item.backdrop_path) {
    heroBackdrop.style.backgroundImage = `url(${IMG_BACKDROP + item.backdrop_path})`;
  }
  heroPlayBtn.onclick = () => openPlayer(featuredItem);
  heroWatchlistBtn.onclick = () => toggleWatchlist(featuredItem);
}

function renderRow(title, items) {
  if (!items || items.length === 0) return;
  const section = document.createElement('section');
  section.className = 'row-section';

  const header = document.createElement('h2');
  header.className = 'row-header';
  header.textContent = title;
  section.appendChild(header);

  const row = document.createElement('div');
  row.className = 'row-cards';

  items.forEach(item => {
    if (!item.poster_path && !item.poster) return;
    const card = createCard(item);
    row.appendChild(card);
  });

  section.appendChild(row);
  mainContainer.appendChild(section);
}

function createCard(item) {
  const card = document.createElement('div');
  card.className = 'card';
  const posterUrl = item.poster || (item.poster_path ? (IMG_POSTER + item.poster_path) : '');
  const title = item.title || item.name || 'Untitled';
  const rating = (item.vote_average || item.rating || 0).toFixed(1);
  const year = (item.release_date || item.first_air_date || item.year || '').substring(0, 4);

  card.innerHTML = `
    <img src="${posterUrl}" alt="${title}" class="card-poster" loading="lazy">
    <div class="card-info">
      <div class="card-title">${title}</div>
      <div class="card-meta">
        <span>★ ${rating}</span>
        <span>${year}</span>
      </div>
    </div>
  `;

  card.addEventListener('click', () => openPlayer(item));
  return card;
}

async function loadGrid(type, url) {
  mainContainer.innerHTML = '<div style="text-align:center; padding: 40px; color: #94A3B8;">Loading...</div>';
  try {
    const data = await fetchJson(url);
    const items = data.results || [];
    mainContainer.innerHTML = '';
    const grid = document.createElement('div');
    grid.className = 'grid-container';
    items.forEach(item => {
      const mediaType = item.media_type || (type === 'tv' ? 'tv' : 'movie');
      if (item.poster_path) {
        grid.appendChild(createCard({ ...item, media_type: mediaType }));
      }
    });
    mainContainer.appendChild(grid);
  } catch (err) {
    mainContainer.innerHTML = `<div style="text-align:center; padding: 40px; color: #EF4444;">Error: ${err.message}</div>`;
  }
}

// Player Logic
async function openPlayer(item) {
  currentMedia = item;
  const isTv = (item.media_type === 'tv' || item.type === 'tv');
  const mediaId = item.id;
  currentSeason = item.season || 1;
  currentEpisode = item.episode || 1;

  playerTitle.textContent = item.title || item.name;
  saveHistory(item);

  if (isTv) {
    episodeBar.style.display = 'flex';
    await setupTvSelectors(mediaId);
  } else {
    episodeBar.style.display = 'none';
  }

  updatePlayerSrc();
  playerModal.style.display = 'flex';
}

function updatePlayerSrc() {
  if (!currentMedia) return;
  const isTv = (currentMedia.media_type === 'tv' || currentMedia.type === 'tv');
  const sObj = SERVERS.find(s => s.key === currentServer) || SERVERS[0];
  let url = '';

  if (isTv) {
    url = sObj.tv.replace('{id}', currentMedia.id).replace('{s}', currentSeason).replace('{e}', currentEpisode);
  } else {
    url = sObj.movie.replace('{id}', currentMedia.id);
  }

  playerIframe.src = url;
}

async function setupTvSelectors(tvId) {
  try {
    const detail = await fetchJson(`${TMDB_BASE}/tv/${tvId}?api_key=${TMDB_KEY}`);
    const seasons = (detail.seasons || []).filter(s => s.season_number > 0);

    seasonSelect.innerHTML = '';
    seasons.forEach(s => {
      const opt = document.createElement('option');
      opt.value = s.season_number;
      opt.textContent = `Season ${s.season_number}`;
      if (s.season_number === currentSeason) opt.selected = true;
      seasonSelect.appendChild(opt);
    });

    seasonSelect.onchange = async () => {
      currentSeason = parseInt(seasonSelect.value, 10);
      currentEpisode = 1;
      await updateEpisodeOptions(tvId, currentSeason);
      updatePlayerSrc();
    };

    await updateEpisodeOptions(tvId, currentSeason);
  } catch (e) {
    console.error('Failed to load seasons:', e);
  }
}

async function updateEpisodeOptions(tvId, sNum) {
  try {
    const sData = await fetchJson(`${TMDB_BASE}/tv/${tvId}/season/${sNum}?api_key=${TMDB_KEY}`);
    episodeSelect.innerHTML = '';
    (sData.episodes || []).forEach(ep => {
      const opt = document.createElement('option');
      opt.value = ep.episode_number;
      opt.textContent = `Ep ${ep.episode_number} - ${ep.name}`;
      if (ep.episode_number === currentEpisode) opt.selected = true;
      episodeSelect.appendChild(opt);
    });

    episodeSelect.onchange = () => {
      currentEpisode = parseInt(episodeSelect.value, 10);
      updatePlayerSrc();
    };
  } catch (e) {
    console.error('Failed to load episodes:', e);
  }
}

function initPlayerControls() {
  closePlayerBtn.onclick = () => {
    playerIframe.src = '';
    playerModal.style.display = 'none';
  };

  serverSelect.onchange = () => {
    currentServer = serverSelect.value;
    updatePlayerSrc();
  };

  prevEpBtn.onclick = () => {
    if (currentEpisode > 1) {
      currentEpisode--;
      episodeSelect.value = currentEpisode;
      updatePlayerSrc();
    }
  };

  nextEpBtn.onclick = () => {
    currentEpisode++;
    episodeSelect.value = currentEpisode;
    updatePlayerSrc();
  };

  // Close on outside click
  playerModal.onclick = (e) => {
    if (e.target === playerModal) {
      playerIframe.src = '';
      playerModal.style.display = 'none';
    }
  };
}

// Watchlist & History Storage
function getHistory() {
  try {
    return JSON.parse(localStorage.getItem('vb_history') || '[]');
  } catch { return []; }
}

function saveHistory(item) {
  try {
    let hist = getHistory();
    hist = hist.filter(h => h.id !== item.id);
    hist.unshift({
      id: item.id,
      title: item.title || item.name,
      poster: item.poster || (IMG_POSTER + item.poster_path),
      media_type: item.media_type || item.type || 'movie',
      rating: item.vote_average || 0,
      season: currentSeason,
      episode: currentEpisode
    });
    localStorage.setItem('vb_history', JSON.stringify(hist.slice(0, 20)));
  } catch {}
}

function toggleWatchlist(item) {
  try {
    let list = JSON.parse(localStorage.getItem('vb_watchlist') || '[]');
    const idx = list.findIndex(i => i.id === item.id);
    if (idx !== -1) {
      list.splice(idx, 1);
      alert('Removed from watchlist');
    } else {
      list.unshift(item);
      alert('Added to watchlist');
    }
    localStorage.setItem('vb_watchlist', JSON.stringify(list));
  } catch {}
}

function loadWatchlist() {
  mainContainer.innerHTML = '';
  const list = JSON.parse(localStorage.getItem('vb_watchlist') || '[]');
  if (list.length === 0) {
    mainContainer.innerHTML = '<div style="text-align:center; padding: 40px; color: #94A3B8;">Watchlist is empty.</div>';
    return;
  }
  const grid = document.createElement('div');
  grid.className = 'grid-container';
  list.forEach(item => grid.appendChild(createCard(item)));
  mainContainer.appendChild(grid);
}

async function fetchJson(url) {
  const resp = await fetch(url);
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return await resp.json();
}
