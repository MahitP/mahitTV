/*
 * The Home tab: the house drawn in 3D on one canvas (an open-roof dollhouse of the real home, app/assets/house.json, the same
 * projection as the Android app's HouseView), its lights cool when off and warm when on. The arrows move between
 * the four lights (the TV is only scenery), OK switches the focused one. The web app can't reach Kasa plugs (TVs
 * block raw sockets), so here the switches only show; the Android app switches the real lights.
 */
(function () {
  'use strict';

  var H = window.HOUSE;
  if (!H) return;

  // house.json's names, as literal tr() calls so tools/translate.py packs them (the Android app's HouseView uses the same).
  var NAMES = {"Parents' Room": tr('Parents\' Room'), 'My Room': tr('My Room'), 'Living Room': tr('Living Room'),
    'Lamp': tr('Lamp'), 'Lightbulb': tr('Lightbulb'), 'Tube Light': tr('Tube Light')};
  function name(text) { return NAMES[text] || text; }

  var canvas, ctx, caption;
  var shown = false, focused = false, focus = 0, frame = 0, last = 0;
  var proj = {s: 1, ox: 0, oy: 0, cos: 1, sin: 0, sp: 1, cp: 0};
  var fixtures = [], rooms = {}, solids = [];

  /** A spring (response in seconds, damping fraction), stepped by the frame's time. */
  function Spring(response, damping, value) {
    this.k = Math.pow(2 * Math.PI / response, 2);
    this.c = 4 * Math.PI * damping / response;
    this.x = value; this.v = 0; this.to = value;
  }
  Spring.prototype.step = function (dt) {
    // Small steps keep a stiff spring stable on a slow frame.
    for (var t = 0; t < dt; t += 1 / 240) {
      var h = Math.min(1 / 240, dt - t), a = -this.k * (this.x - this.to) - this.c * this.v;
      this.v += a * h; this.x += this.v * h;
    }
    if (Math.abs(this.x - this.to) < 0.0005 && Math.abs(this.v) < 0.001) { this.x = this.to; this.v = 0; return false; }
    return true;
  };

  // MARK: - Geometry

  function setup() {
    var c = H.camera, yaw = c.yaw * Math.PI / 180, pitch = c.pitch * Math.PI / 180;
    proj.cos = Math.cos(yaw); proj.sin = Math.sin(yaw); proj.sp = Math.sin(pitch); proj.cp = Math.cos(pitch);
    proj.s = 1; proj.ox = 0; proj.oy = 0;
    // Fit the floor's corners and the wall tops into camera.box.
    var W = H.size[0], D = H.size[1], minU = 1e9, maxU = -1e9, minV = 1e9, maxV = -1e9;
    var corners = [[0, 0], [W, 0], [W, D], [0, D]];
    for (var i = 0; i < 4; i++) {
      for (var z = 0; z <= 2.6; z += 2.6) {
        var p = raw(corners[i][0], corners[i][1], z);
        minU = Math.min(minU, p[0]); maxU = Math.max(maxU, p[0]); minV = Math.min(minV, p[1]); maxV = Math.max(maxV, p[1]);
      }
    }
    var b = c.box, s = Math.min((b[2] - b[0]) / (maxU - minU), (b[3] - b[1]) / (maxV - minV));
    proj.s = s;
    proj.ox = (b[0] + b[2]) / 2 - s * (minU + maxU) / 2;
    proj.oy = (b[1] + b[3]) / 2 - s * (minV + maxV) / 2;

    for (i = 0; i < H.rooms.length; i++) rooms[H.rooms[i].id] = {def: H.rooms[i], glow: new Spring(0.45, 0.9, 0)};
    solids = [];
    for (i = 0; i < H.walls.length; i++) {
      // Walls are thin boxes, cut into 1 m pieces so each sorts by its own depth.
      var w = H.walls[i], x0 = w.from[0], y0 = w.from[1], x1 = w.to[0], y1 = w.to[1];
      var len = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)), n = Math.max(1, Math.ceil(len)), t2 = 0.12;
      for (var k = 0; k < n; k++) {
        // Each piece overlaps the next by 2 cm, so no seam shows between them.
        var e = k < n - 1 ? 0.02 : 0, ax = x0 + (x1 - x0) * k / n, ay = y0 + (y1 - y0) * k / n;
        var bx = x0 + (x1 - x0) * (k + 1) / n + (x1 > x0 ? e : 0), by = y0 + (y1 - y0) * (k + 1) / n + (y1 > y0 ? e : 0);
        var box = x0 === x1 ? [ax - t2 / 2, Math.min(ay, by), 0, t2, Math.abs(by - ay), w.h] : [Math.min(ax, bx), ay - t2 / 2, 0, Math.abs(bx - ax), t2, w.h];
        solids.push({box: box, color: 'wall', top: 'wall-top', rooms: roomsAt(box)});
      }
    }
    for (i = 0; i < H.furniture.length; i++) {
      var f = H.furniture[i];
      solids.push({box: f.box, color: f.color, rooms: [f.room], flat: f.kind === 'rug', kind: f.kind});
    }
    for (i = 0; i < solids.length; i++) {
      var o = solids[i].box;
      solids[i].depth = depth(o[0] + o[3] / 2, o[1] + o[4] / 2, 0);
    }
    solids.sort(function (a, b2) { return a.depth - b2.depth; });
    fixtures = [];
    for (i = 0; i < H.fixtures.length; i++) {
      var d = H.fixtures[i], at = d.at, z2 = d.kind === 'lamp' ? (d.height || 1.3) + 0.22 : at[2];
      var sp = pt(at[0], at[1], z2);
      fixtures.push({def: d, x: sp[0], y: sp[1], on: false, known: false, grow: new Spring(0.3, 0.8, 0), lit: new Spring(0.45, 0.9, 0),
        depth: depth(at[0], at[1], at[2])});
    }
  }

  function roomsAt(box) {
    // The rooms on either side of a wall piece.
    var cx = box[0] + box[3] / 2, cy = box[1] + box[4] / 2, out = [];
    for (var i = 0; i < H.rooms.length; i++) {
      var r = H.rooms[i].rect;
      if (cx >= r[0] - 0.2 && cx <= r[2] + 0.2 && cy >= r[1] - 0.2 && cy <= r[3] + 0.2) out.push(H.rooms[i].id);
    }
    return out;
  }

  function raw(x, y, z) {
    var dx = x - H.size[0] / 2, dy = y - H.size[1] / 2;
    var rx = dx * proj.cos - dy * proj.sin, ry = dx * proj.sin + dy * proj.cos;
    return [rx, ry * proj.sp - z * proj.cp];
  }

  function pt(x, y, z) {
    var p = raw(x, y, z);
    return [proj.ox + p[0] * proj.s, proj.oy + p[1] * proj.s];
  }

  function depth(x, y, z) {
    var dx = x - H.size[0] / 2, dy = y - H.size[1] / 2;
    return (dx * proj.sin + dy * proj.cos) * proj.cp + z * proj.sp;
  }

  // MARK: - Color

  function hex(c) { return [parseInt(c.substr(1, 2), 16), parseInt(c.substr(3, 2), 16), parseInt(c.substr(5, 2), 16)]; }
  var PAL = {cool: {}, warm: {}};
  (function () {
    for (var k in H.palette.cool) if (H.palette.cool.hasOwnProperty(k)) { PAL.cool[k] = hex(H.palette.cool[k]); PAL.warm[k] = hex(H.palette.warm[k]); }
  })();
  var GLOW = hex(H.palette.glow);

  function color(role, g, shade) {
    var a = PAL.cool[role] || [150, 150, 160], b = PAL.warm[role] || a, out = [];
    for (var i = 0; i < 3; i++) out.push(Math.max(0, Math.min(255, Math.round((a[i] + (b[i] - a[i]) * g) * shade))));
    return 'rgb(' + out[0] + ',' + out[1] + ',' + out[2] + ')';
  }

  function glowOf(ids) {
    var g = 0;
    for (var i = 0; i < ids.length; i++) if (rooms[ids[i]]) g = Math.max(g, rooms[ids[i]].glow.x);
    return g;
  }

  // MARK: - Drawing

  function poly(points, fill) {
    ctx.beginPath();
    ctx.moveTo(points[0][0], points[0][1]);
    for (var i = 1; i < points.length; i++) ctx.lineTo(points[i][0], points[i][1]);
    ctx.closePath();
    ctx.fillStyle = fill;
    ctx.fill();
  }

  /** A box: its faces that look at the camera (the front, +y, and the side, +x, at this yaw), then its top. */
  function drawBox(o, role, g, topRole) {
    var x = o[0], y = o[1], z = o[2], w = o[3], d = o[4], h = o[5];
    if (proj.cos > 0) poly([pt(x, y + d, z), pt(x + w, y + d, z), pt(x + w, y + d, z + h), pt(x, y + d, z + h)], color(role, g, 0.94));
    if (proj.sin > 0) poly([pt(x + w, y, z), pt(x + w, y + d, z), pt(x + w, y + d, z + h), pt(x + w, y, z + h)], color(role, g, 0.84));
    else poly([pt(x, y, z), pt(x, y + d, z), pt(x, y + d, z + h), pt(x, y, z + h)], color(role, g, 0.84));
    poly([pt(x, y, z + h), pt(x + w, y, z + h), pt(x + w, y + d, z + h), pt(x, y + d, z + h)], color(topRole || role, g, topRole ? 1 : 1.08));
  }

  function floorGlow(f) {
    var g = f.lit.x;
    if (g < 0.01) return;
    var r = rooms[f.def.room].def.rect, at = f.def.glow || f.def.at, c = pt(at[0], at[1], 0);
    ctx.save();
    ctx.beginPath();
    var q = [pt(r[0], r[1], 0), pt(r[2], r[1], 0), pt(r[2], r[3], 0), pt(r[0], r[3], 0)];
    ctx.moveTo(q[0][0], q[0][1]);
    for (var i = 1; i < 4; i++) ctx.lineTo(q[i][0], q[i][1]);
    ctx.closePath();
    ctx.clip();
    // An ellipse on the floor: 3 m across, squashed by the pitch.
    ctx.translate(c[0], c[1]);
    ctx.scale(1, proj.sp);
    var R = 3 * proj.s, grad = ctx.createRadialGradient(0, 0, 0, 0, 0, R);
    grad.addColorStop(0, 'rgba(' + GLOW[0] + ',' + GLOW[1] + ',' + GLOW[2] + ',' + (0.35 * g) + ')');
    grad.addColorStop(1, 'rgba(' + GLOW[0] + ',' + GLOW[1] + ',' + GLOW[2] + ',0)');
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = grad;
    ctx.fillRect(-R, -R, 2 * R, 2 * R);
    ctx.restore();
  }

  function drawFixture(f) {
    var at = f.def.at, g = f.lit.x, grow = 1 + 0.25 * f.grow.x, s = proj.s;
    ctx.save();
    ctx.translate(f.x, f.y);
    ctx.scale(grow, grow);
    ctx.translate(-f.x, -f.y);
    if (f.grow.x > 0.01) {
      // The focus: a soft white halo behind the light and a thin ring round it.
      var halo = ctx.createRadialGradient(f.x, f.y, 0, f.x, f.y, 0.62 * s);
      halo.addColorStop(0, 'rgba(255,255,255,' + (0.55 * f.grow.x) + ')');
      halo.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = halo;
      ctx.fillRect(f.x - s, f.y - s, 2 * s, 2 * s);
      ctx.strokeStyle = 'rgba(255,255,255,' + (0.9 * f.grow.x) + ')';
      ctx.lineWidth = 2.5 / grow;
      ctx.beginPath(); ctx.arc(f.x, f.y, 0.36 * s, 0, 2 * Math.PI); ctx.stroke();
    }
    if (f.def.kind === 'bulb') {
      var top = pt(at[0], at[1], 2.6);
      ctx.strokeStyle = 'rgba(40,44,52,0.55)';
      ctx.lineWidth = Math.max(1, 0.02 * s);
      ctx.beginPath(); ctx.moveTo(top[0], top[1]); ctx.lineTo(f.x, f.y - 0.12 * s); ctx.stroke();
      bulb(f.x, f.y, 0.16 * s, g);
    } else if (f.def.kind === 'tube') {
      tube(f, g);
    } else {
      // The lamp: a base, a pole and a shade that glows.
      var h = f.def.height || 1.3, base = pt(at[0], at[1], 0), shadeTop = pt(at[0], at[1], h + 0.45), shadeBottom = pt(at[0], at[1], h);
      ctx.fillStyle = color('wood', g, 0.7);
      ctx.save(); ctx.translate(base[0], base[1]); ctx.scale(1, proj.sp); ctx.beginPath(); ctx.arc(0, 0, 0.2 * s, 0, 2 * Math.PI); ctx.fill(); ctx.restore();
      ctx.strokeStyle = color('wood', g, 0.6);
      ctx.lineWidth = Math.max(1.5, 0.035 * s);
      ctx.beginPath(); ctx.moveTo(base[0], base[1]); ctx.lineTo(shadeBottom[0], shadeBottom[1]); ctx.stroke();
      var rt = 0.17 * s, rb = 0.26 * s;
      if (g > 0.01) bloom(f.x, f.y, 0.9 * s, g);
      ctx.beginPath();
      ctx.moveTo(shadeTop[0] - rt, shadeTop[1]);
      ctx.lineTo(shadeTop[0] + rt, shadeTop[1]);
      ctx.lineTo(shadeBottom[0] + rb, shadeBottom[1]);
      ctx.lineTo(shadeBottom[0] - rb, shadeBottom[1]);
      ctx.closePath();
      ctx.fillStyle = mix([168, 174, 188], [255, 222, 172], g);
      ctx.fill();
    }
    ctx.restore();
  }

  /** A wall tube light: a bar with dark end caps along the wall; lit, a warm core and a bloom. */
  function tube(f, g) {
    var d = f.def, at = d.at, half = (d.length || 1.2) / 2, s = proj.s;
    var ax = d.along === 'x' ? at[0] - half : at[0], ay = d.along === 'x' ? at[1] : at[1] - half;
    var bx = d.along === 'x' ? at[0] + half : at[0], by = d.along === 'x' ? at[1] : at[1] + half;
    var a = pt(ax, ay, at[2]), b = pt(bx, by, at[2]);
    if (g > 0.01) bloom(f.x, f.y, 1.1 * s, g);
    ctx.lineCap = 'round';
    ctx.strokeStyle = 'rgb(48,51,58)';
    ctx.lineWidth = Math.max(3, 0.11 * s);
    ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
    var a2 = [a[0] + (b[0] - a[0]) * 0.09, a[1] + (b[1] - a[1]) * 0.09], b2 = [a[0] + (b[0] - a[0]) * 0.91, a[1] + (b[1] - a[1]) * 0.91];
    ctx.strokeStyle = mix([206, 211, 220], [255, 233, 198], g);
    ctx.lineWidth = Math.max(2, 0.08 * s);
    ctx.beginPath(); ctx.moveTo(a2[0], a2[1]); ctx.lineTo(b2[0], b2[1]); ctx.stroke();
    if (g > 0.01) {
      ctx.strokeStyle = 'rgba(255,250,240,' + g + ')';
      ctx.lineWidth = Math.max(1, 0.035 * s);
      ctx.beginPath(); ctx.moveTo(a2[0], a2[1]); ctx.lineTo(b2[0], b2[1]); ctx.stroke();
    }
    ctx.lineCap = 'butt';
  }

  function mix(a, b, t) {
    return 'rgb(' + Math.round(a[0] + (b[0] - a[0]) * t) + ',' + Math.round(a[1] + (b[1] - a[1]) * t) + ',' + Math.round(a[2] + (b[2] - a[2]) * t) + ')';
  }

  function bloom(x, y, R, g) {
    var grad = ctx.createRadialGradient(x, y, 0, x, y, R);
    grad.addColorStop(0, 'rgba(255,200,130,' + (0.55 * g) + ')');
    grad.addColorStop(1, 'rgba(255,192,122,0)');
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = grad;
    ctx.fillRect(x - R, y - R, 2 * R, 2 * R);
    ctx.restore();
  }

  function bulb(x, y, r, g) {
    if (g > 0.01) bloom(x, y, r * 6, g);
    var grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, 0, x, y, r);
    grad.addColorStop(0, mix([246, 248, 252], [255, 251, 238], g));
    grad.addColorStop(1, mix([176, 182, 194], [255, 196, 120], g));
    ctx.fillStyle = grad;
    ctx.beginPath(); ctx.arc(x, y, r, 0, 2 * Math.PI); ctx.fill();
  }

  function draw() {
    ctx.clearRect(0, 0, 1920, 1080);
    var i, k, r;
    // A soft shadow under the house, so it sits on the frosted background.
    var c0 = pt(H.size[0] / 2, H.size[1] / 2, 0), shadow = ctx.createRadialGradient(0, 0, 0, 0, 0, 1);
    shadow.addColorStop(0, 'rgba(0,0,0,0.32)');
    shadow.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.save();
    ctx.translate(c0[0], c0[1] + 0.35 * proj.s);
    ctx.scale(7.4 * proj.s, 7.4 * proj.s * proj.sp);
    ctx.fillStyle = shadow;
    ctx.fillRect(-1, -1, 2, 2);
    ctx.restore();
    // The base slab, then the floors.
    drawBox([-0.12, -0.12, -0.25, H.size[0] + 0.24, H.size[1] + 0.24, 0.25], 'wall', 0, 'wall-top');
    for (k in rooms) {
      if (!rooms.hasOwnProperty(k)) continue;
      r = rooms[k].def.rect;
      poly([pt(r[0], r[1], 0), pt(r[2], r[1], 0), pt(r[2], r[3], 0), pt(r[0], r[3], 0)], color('floor-' + rooms[k].def.floor, rooms[k].glow.x, 1));
    }
    for (i = 0; i < solids.length; i++) if (solids[i].flat) drawBox(solids[i].box, solids[i].color, glowOf(solids[i].rooms));
    for (i = 0; i < fixtures.length; i++) floorGlow(fixtures[i]);
    // Everything that stands, back to front, the lights among them.
    var lamps = [], bulbs = [], f = 0;
    for (i = 0; i < fixtures.length; i++) (fixtures[i].def.kind === 'lamp' ? lamps : bulbs).push(fixtures[i]);
    lamps.sort(function (a, b) { return a.depth - b.depth; });
    for (i = 0; i < solids.length; i++) {
      while (f < lamps.length && lamps[f].depth < solids[i].depth) drawFixture(lamps[f++]);
      if (!solids[i].flat) drawBox(solids[i].box, solids[i].color, glowOf(solids[i].rooms), solids[i].top);
    }
    while (f < lamps.length) drawFixture(lamps[f++]);
    // The ceiling and wall lights hang above everything under them: drawn last.
    for (i = 0; i < bulbs.length; i++) drawFixture(bulbs[i]);
  }

  // MARK: - Animation

  function tick(now) {
    frame = 0;
    var dt = last ? Math.min(0.05, (now - last) / 1000) : 1 / 60, moving = false, i, k;
    last = now;
    for (i = 0; i < fixtures.length; i++) {
      fixtures[i].grow.to = focused && i === focus ? 1 : 0;
      fixtures[i].lit.to = fixtures[i].on ? 1 : 0;
      if (fixtures[i].grow.step(dt)) moving = true;
      if (fixtures[i].lit.step(dt)) moving = true;
    }
    for (k in rooms) {
      if (!rooms.hasOwnProperty(k)) continue;
      var on = 0;
      for (i = 0; i < fixtures.length; i++) if (fixtures[i].def.room === k) on = Math.max(on, fixtures[i].on ? 1 : 0);
      rooms[k].glow.to = on;
      if (rooms[k].glow.step(dt)) moving = true;
    }
    draw();
    if (moving && shown) frame = requestAnimationFrame(tick);
    else last = 0;
  }

  function kick() {
    if (!frame && canvas) { last = 0; frame = requestAnimationFrame(tick); }
  }

  function roomName(id) {
    for (var i = 0; i < H.rooms.length; i++) if (H.rooms[i].id === id) return name(H.rooms[i].name);
    return id;
  }

  function showCaption() {
    var f = fixtures[focus];
    if (!focused || !f) { caption.className = 'caption'; return; }
    caption.textContent = roomName(f.def.room) + '  ·  ' + name(f.def.name) + '  ·  ' + (f.on ? tr('On') : tr('Off'));
    caption.className = 'caption on';
  }

  /** The nearest light on screen in the pressed direction, or -1. */
  function next(dx, dy) {
    var a = fixtures[focus], best = -1, bestScore = 1e9;
    for (var i = 0; i < fixtures.length; i++) {
      if (i === focus) continue;
      var ex = fixtures[i].x - a.x, ey = fixtures[i].y - a.y, along = dx ? ex * dx : ey * dy, across = Math.abs(dx ? ey : ex);
      if (along <= 4) continue;
      var score = along + 2 * across;
      if (score < bestScore) { bestScore = score; best = i; }
    }
    return best;
  }

  window.House = {
    init: function (layer) {
      canvas = layer.querySelector('canvas');
      caption = layer.querySelector('.caption');
      ctx = canvas.getContext('2d');
      setup();
      // The light nearest the middle of the screen gets the focus first.
      var bestD = 1e9;
      for (var i = 0; i < fixtures.length; i++) {
        var d = Math.abs(fixtures[i].x - 960) + Math.abs(fixtures[i].y - 560);
        if (d < bestD) { bestD = d; focus = i; }
      }
      draw();
    },
    show: function (on) { shown = on; if (on) kick(); },
    focus: function (on) { focused = on; showCaption(); kick(); },
    /** false when there's no light that way and the press was ↑: the tab bar takes the focus. */
    move: function (dx, dy) {
      var n = next(dx, dy);
      if (n < 0) return !(dy < 0);
      focus = n;
      showCaption();
      kick();
      return true;
    },
    toggle: function () {
      var f = fixtures[focus];
      if (!f) return;
      f.on = !f.on;
      showCaption();
      kick();
    },
    /** For Welly's cards and the preview: a light by id, on or off. */
    set: function (id, on) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id) fixtures[i].on = on;
      showCaption();
      kick();
    },
    /** Moves the focus to a light by id (the screenshots, Welly's home cards). */
    select: function (id) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id) focus = i;
      showCaption();
      kick();
    },
    state: function (id) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id) return fixtures[i].on;
      return false;
    },
    roomName: roomName,
    fixtureName: function (id) {
      for (var i = 0; i < H.fixtures.length; i++) if (H.fixtures[i].id === id) return name(H.fixtures[i].name);
      return id;
    },
    roomOf: function (id) {
      for (var i = 0; i < H.fixtures.length; i++) if (H.fixtures[i].id === id) return H.fixtures[i].room;
      return '';
    }
  };
})();
