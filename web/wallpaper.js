/* The wallpaper catalogue and policy shared with the Android app. ES5 for older TVs. */
var Wallpaper = (function () {
  'use strict';

  var horizons = ['horizon:auto', 'horizon:light', 'horizon:dark'];
  var aerials = [
    {
      id: 'aerial:yosemite',
      asset: '4109D42A-D717-46A7-A9A2-FE53A82B25C0',
      title: 'Yosemite Valley',
      video: 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/Y011_C001_SDR_2K_AVC.mov',
      preview: 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/Y011_C001_900x580.png'
    },
    {
      id: 'aerial:iceland',
      asset: '8590D0C5-E344-4FAC-A39A-FD7BC652AEDA',
      title: 'Iceland Coast',
      video: 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/I003_C004_SDR_2K_AVC.mov',
      preview: 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/I003_C004_900x580.png'
    },
    {
      id: 'aerial:north-america-aurora',
      asset: '737E9E24-49BE-4104-9B72-F352DE1AD2BF',
      title: 'North America Aurora',
      video: 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT314_139M_170NC_NORTH_AMERICA_AURORA__COMP_v22_SDR_20181206_v12CC_SDR_2K_AVC.mov',
      preview: 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/GMT314_139M_900x580.png'
    },
    {
      id: 'aerial:palau-coral',
      asset: 'F07CC61B-30FC-4614-BDAD-3240B61F6793',
      title: 'Palau Coral Colors',
      video: 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/PA_A004_C003_SDR_20190719_SDR_2K_AVC.mov',
      preview: 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/PA_A004_C003_900x580.png'
    },
    {
      id: 'aerial:new-york',
      asset: '3BA0CFC7-E460-4B59-A817-B97F9EBB9B89',
      title: 'New York from Above',
      video: 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_N008_C009_PS_v01_SDR_PS_20180925_SDR_2K_AVC.mov',
      preview: 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/N008_C009_900x580.png'
    },
    {
      id: 'aerial:sonoma-coast',
      asset: 'FE876489-CBD5-479B-A8F0-1B67F0741CEA',
      title: 'Sonoma Horizon',
      video: '',
      preview: 'https://sylvan.apple.com/itunes-assets/Aerials126/v4/51/ff/08/51ff0824-8da5-78f0-e218-9e61264965bb/W010_C003_F01@2x.png'
    }
  ];
  var solids = [
    {id: 'studio:black', color: '#000000'},
    {id: 'studio:space-gray', color: '#1C1C1E'},
    {id: 'studio:midnight-blue', color: '#121A2A'},
    {id: 'studio:deep-forest', color: '#11221C'},
    {id: 'studio:teal', color: '#1B3B3A'},
    {id: 'studio:warm-stone', color: '#8E8E93'},
    {id: 'studio:silver-mist', color: '#D1D5DB'}
  ];
  var horizonGeometry = [
    [-0.08, 0.69, 0.83],
    [0.53, 0.48, 0.78],
    [1.06, 1.04, 1.04]
  ];
  var allowedMedia = [];
  var i;

  for (i = 0; i < aerials.length; i++) {
    if (aerials[i].video) allowedMedia.push(aerials[i].video);
    allowedMedia.push(aerials[i].preview);
  }

  function canonical(id) {
    for (var at = 0; at < aerials.length; at++) if (aerials[at].id === id) return aerials[at];
    return null;
  }

  function copyAerial(aerial) {
    return {
      id: aerial.id,
      asset: aerial.asset,
      title: aerial.title,
      video: aerial.video,
      preview: aerial.preview
    };
  }

  function copyAerials() {
    var copy = [];
    for (var at = 0; at < aerials.length; at++) copy.push(copyAerial(aerials[at]));
    return copy;
  }

  function copySolids() {
    var copy = [];
    for (var at = 0; at < solids.length; at++) copy.push({id: solids[at].id, color: solids[at].color});
    return copy;
  }

  function copyHorizonGeometry() {
    var copy = [];
    for (var at = 0; at < horizonGeometry.length; at++) copy.push(horizonGeometry[at].slice(0));
    return copy;
  }

  function find(id) {
    var aerial = canonical(id);
    return aerial ? copyAerial(aerial) : null;
  }

  function valid(id) {
    var at;
    if (id === 'live:aerial' || horizons.indexOf(id) >= 0 || canonical(id)) return true;
    for (at = 0; at < solids.length; at++) if (solids[at].id === id) return true;
    return false;
  }

  function normalize(id) {
    return valid(id) ? id : 'horizon:auto';
  }

  function dark(id, weatherNight) {
    id = normalize(id);
    return id === 'horizon:dark' || (id !== 'horizon:light' && weatherNight === true);
  }

  function legacyAsset(id) {
    if (id === 'aerial:yosemite') return 'valley.jpg';
    if (id === 'aerial:iceland') return 'coast.jpg';
    if (id === 'aerial:north-america-aurora') return 'aurora.jpg';
    if (id === 'aerial:new-york') return 'earth.jpg';
    return '';
  }

  function allowed(value) {
    return typeof value === 'string' && value.indexOf('https://sylvan.apple.com/') === 0 && allowedMedia.indexOf(value) >= 0;
  }

  function palette(dark) {
    return dark ? ['#0D131F', '#1C2638', '#151E2D', '#080B12']
      : ['#B8C7D6', '#F5EBD6', '#DCE6EF', '#7E9BB5'];
  }

  function horizonVignette(dark) {
    return dark ? 0.18 : 0.12;
  }

  function media(zone, mediaApp) {
    return mediaApp === true && (zone === 'shelf' || zone === 'hero');
  }

  function mediaKind(kind) {
    return kind === 'movie' || kind === 'tv';
  }

  function mediaId(item) {
    var id = item && (item.tmdb !== undefined ? item.tmdb : item.tmdbId !== undefined ? item.tmdbId : item.id);
    id = Number(id);
    return isFinite(id) && id > 0 ? id : 0;
  }

  function sameApp(item, app) {
    if (app.pkg) return item.pkg === app.pkg;
    return !!app.appKey && (item.appKey === app.appKey || item.app === app.appKey);
  }

  /** Pure media-background policy. History is copied into a normalized item; inputs are never changed. */
  function selectMedia(history, picks, app, nowPlaying) {
    var best = null, bestAt = -Infinity, at, id, item, pick;
    if (!app || app.media !== true) return null;
    if (app.music === true) {
      if (nowPlaying && nowPlaying.active === true && nowPlaying.title && nowPlaying.artwork
          && (!app.pkg || !nowPlaying.pkg || nowPlaying.pkg === app.pkg)) {
        return {source: 'nowPlaying', item: nowPlaying};
      }
      return null;
    }
    for (at = 0; history && at < history.length; at++) {
      item = history[at];
      id = mediaId(item);
      if (!id || !mediaKind(item.kind) || !sameApp(item, app)) continue;
      if (best === null || Number(item.at || 0) > bestAt) {
        bestAt = Number(item.at || 0);
        best = {
          kind: item.kind,
          tmdb: id,
          title: item.title || '',
          pkg: item.pkg || '',
          appKey: item.appKey || item.app || '',
          service: item.app || ''
        };
      }
    }
    if (best) return {source: 'history', item: best};
    for (at = 0; picks && at < picks.length; at++) {
      pick = picks[at];
      if (mediaKind(pick.kind) && mediaId(pick) && sameApp(pick, app)) return {source: 'featured', item: pick};
    }
    return null;
  }

  return {
    aerials: copyAerials(),
    solids: copySolids(),
    normalize: normalize,
    dark: dark,
    legacyAsset: legacyAsset,
    find: find,
    allowed: allowed,
    palette: palette,
    horizonGeometry: copyHorizonGeometry,
    horizonVignette: horizonVignette,
    media: media,
    selectMedia: selectMedia
  };
}());
