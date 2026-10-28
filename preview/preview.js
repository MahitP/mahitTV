/*
 * Preview builds only (webapp.preview): sample picks with the generated photos in preview/art, so the Top Shelf shows
 * before a TMDB key is set. Never packed into the LG or Samsung app. With a key in config.properties, the real picks
 * are used instead.
 */
(function () {
  'use strict';
  if (window.CONFIG && CONFIG.tmdbKey) return;
  function pick(i, file, kind, title, year, meta, app, service, rating, overview, cert, badges) {
    return {id: 9000 + i, kind: kind, title: title, year: year, backdrop: 'preview/' + file + '.jpg', logo: 'preview/' + file + '-logo.png',
      app: app, service: service, meta: meta + ' · ' + service, rating: rating, overview: overview, cert: cert, badges: badges,
      detailed: true};
  }
  window.PREVIEW_PICKS = [
    pick(0, 'neon-meridian', 'movie', 'Neon Meridian', '2026', 'Sci-Fi · 2h 4m', 'appletv', 'Apple TV', 8.1,
      'A courier crosses a drowned city to deliver one last message before the lights go out for good.', 'PG-13', ['4K', 'Dolby Vision', 'Dolby Atmos', 'CC']),
    pick(1, 'long-dunes', 'tv', 'The Long Dunes', '2025', 'Drama · 2 seasons', 'netflix', 'Netflix', 7.6,
      'Two sisters inherit a caravan route across the open desert, and every oasis on it keeps a secret.', 'TV-MA', ['4K', 'HDR10', 'CC']),
    pick(2, 'quiet-peak', 'movie', 'Quiet Peak', '2026', 'Adventure · 1h 52m', 'prime', 'Prime Video', 7.9,
      'A mountain guide’s last season turns into a rescue nobody on the ridge thought was possible.', 'PG', ['4K', 'Dolby Vision', 'CC']),
    pick(3, 'orbital', 'tv', 'Orbital', '2026', 'Sci-Fi · 1 season', 'disney', 'Disney+', 8.4,
      'The crew of a failing station has ninety days to choose who goes home on the only seat left.', 'TV-14', ['4K', 'Dolby Vision', 'Dolby Atmos', 'CC']),
    pick(4, 'tidebreak', 'movie', 'Tidebreak', '2025', 'Thriller · 1h 47m', 'max', 'Max', 7.2,
      'When the storm takes the harbor’s lighthouse dark, its keeper is the only witness to what came ashore.', 'R', ['4K', 'Dolby Atmos', 'CC'])
  ];

  // Keep a resolvable Netflix title newest in the active profile's deterministic preview history.
  if (window.Profiles && Profiles.seedPreview) {
    var seedPreview = Profiles.seedPreview;
    Profiles.seedPreview = function () {
      seedPreview();
      var profile = Profiles.current(), store = Profiles.store(profile);
      if (profile.role === 'guest' || store.get('previewMediaSeeded', false)) return;
      var log = store.get('watchLog', []), netflix = null;
      for (var i = 0; i < log.length; i++) {
        if (log[i].kind === 'tv' && Number(log[i].tmdb) === 9001 && log[i].appKey === 'netflix') {
          netflix = log.splice(i, 1)[0];
          break;
        }
      }
      if (!netflix) netflix = {title: 'The Long Dunes', tmdb: 9001, kind: 'tv', app: 'Netflix', appKey: 'netflix', pos: 720000, dur: 2880000};
      netflix.at = Date.now();
      log.unshift(netflix);
      store.set('watchLog', log.slice(0, 30));
      store.set('previewMediaSeeded', true);
    };
  }

})();
