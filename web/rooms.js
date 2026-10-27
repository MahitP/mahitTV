/*
 * The Home tab: the apartment as a clay diorama on one canvas (app/assets/house.json, written by tools/make_house.py;
 * the same drawing as the Android app's HouseView). Cool when the lights are off, warm around each light that's on.
 * The arrows move between the four lights (the TVs are scenery), OK switches the focused one. The camera swings to
 * the focused light's room on a spring; outside walls lower themselves when they'd stand between the camera and the
 * room. Each light has a dot above it; the focused one carries a pill with its name and state.
 * Motion follows ANIMATIONS-SPEC.md §3 on motion.js's springs: the camera orbit, the walls, the rooms fading back, the
 * warmth spreading from a light at 8.5 m/s, the filament's flash, dead-end bumps, the fans, the screens' standby glint,
 * the idle drift and a lit dot's breath (the last five off with reduce motion).
 * The web app can't reach Kasa plugs (TVs block raw sockets), so here the switches only show.
 */
(function () {
  'use strict';

  var H = window.HOUSE;
  if (!H) return;

  // house.json's names, as literal tr() calls so tools/translate.py packs them (HouseView uses the same).
  var NAMES = {"Parents' Room": tr('Parents\' Room'), 'My Room': tr('My Room'), 'Living Room': tr('Living Room'),
    'Lamp': tr('Lamp'), 'Lightbulb': tr('Lightbulb'), 'Tube Light': tr('Tube Light')};
  function name(text) { return NAMES[text] || text; }

  var W = H.size[0], D = H.size[1], TALL = 2.6, DEG = Math.PI / 180;
  var BOX = H.camera.box, AX = (BOX[0] + BOX[2]) / 2, AY = (BOX[1] + BOX[3]) / 2;
  // tvOS safe area for the pill: 90 px at the sides, under the tab bar at the top, 60 px at the bottom.
  var SAFE = {left: 90, right: 1830, top: 128, bottom: 1020};

  // The warmth spreads from a light this fast (m/s) and pulls back in this long (s); the filament's flash; a fan turns
  // once in FAN_PERIOD s; the screens glint every GLINT_PERIOD s; the idle drift starts after IDLE ms.
  var WAVE_SPEED = 8.5, WAVE_MAX = 7, WAVE_OUT = 0.24, FLASH = 14.2, FAN_PERIOD = 4.5, GLINT_PERIOD = 6, IDLE = 6000;
  // Unfocused rooms: toward the dark background (45% left) and 30% less colour.
  var DIM = 0.45, DESAT = 0.3, BG = [24, 22, 28];
  var M = window.Motion, PR = M.P;

  var canvas, ctx, pills = [], pillAt = 0, pillText = '';
  var shown = false, focused = false, focus = 0, lastKey = 0, clock = 0, ticking = false;

  function Spring(preset, value) { return new M.Spring(preset, value); }
  function still() { return M.instant; }

  // MARK: - The camera

  // The camera swings on CAMERA_ORBIT; the overview keeps the blueprint's orientation.
  var cam = {yaw: Spring(PR.CAMERA_ORBIT, H.camera.yaw).deg(), pitch: Spring(PR.CAMERA_ORBIT, H.camera.pitch).deg(),
    zoom: Spring(PR.CAMERA_ORBIT, 1), cx: Spring(PR.CAMERA_ORBIT, W / 2), cy: Spring(PR.CAMERA_ORBIT, D / 2)};
  // The idle drift's strength (0 to 1), eased in and out.
  var drift = Spring(PR.ROOM_FADE, 0);
  var P = {cos: 1, sin: 0, sp: 1, cp: 0, s: 1, ox: 0, oy: 0};
  var S0 = 1, OVER = null;

  function setAngles(yaw, pitch) {
    P.cos = Math.cos(yaw * DEG); P.sin = Math.sin(yaw * DEG); P.sp = Math.sin(pitch * DEG); P.cp = Math.cos(pitch * DEG);
  }

  function raw(x, y, z) {
    var dx = x - W / 2, dy = y - D / 2;
    var rx = dx * P.cos - dy * P.sin, ry = dx * P.sin + dy * P.cos;
    return [rx, ry * P.sp - z * P.cp];
  }

  function pt(x, y, z) {
    var r = raw(x, y, z);
    return [P.ox + r[0] * P.s, P.oy + r[1] * P.s];
  }

  /** Larger is nearer the viewer (drawn later). */
  function depth(x, y, z) {
    var dx = x - W / 2, dy = y - D / 2;
    return (dx * P.sin + dy * P.cos) * P.cp + z * P.sp;
  }

  /** The projection for a camera (yaw, pitch in degrees; zoom; the world point at the box's centre). */
  function aim(yaw, pitch, zoom, cx, cy) {
    setAngles(yaw, pitch);
    P.s = S0 * zoom;
    var c = raw(cx, cy, 0);
    P.ox = AX - c[0] * P.s;
    P.oy = AY - c[1] * P.s;
  }

  /** The overview: the whole plan and its tall walls fitted into camera.box. */
  function fitOverview() {
    setAngles(H.camera.yaw, H.camera.pitch);
    var minU = 1e9, maxU = -1e9, minV = 1e9, maxV = -1e9, xs = [0, W], ys = [0, D];
    for (var i = 0; i < 2; i++) for (var j = 0; j < 2; j++) for (var z = 0; z <= TALL; z += TALL) {
      var p = raw(xs[i], ys[j], z);
      minU = Math.min(minU, p[0]); maxU = Math.max(maxU, p[0]); minV = Math.min(minV, p[1]); maxV = Math.max(maxV, p[1]);
    }
    S0 = Math.min((BOX[2] - BOX[0]) / (maxU - minU), (BOX[3] - BOX[1]) / (maxV - minV));
    // The world point (on the floor) under the box's centre.
    var u = (minU + maxU) / 2, v = (minV + maxV) / 2, rx = u, ry = v / P.sp;
    OVER = {yaw: H.camera.yaw, pitch: H.camera.pitch, zoom: 1,
      cx: rx * P.cos + ry * P.sin + W / 2, cy: -rx * P.sin + ry * P.cos + D / 2};
  }

  function target(c) {
    // The shortest way round to the new yaw, so the spring never spins the long way.
    var y = cam.yaw.to, d = ((c.yaw - y) % 360 + 540) % 360 - 180;
    cam.yaw.to = y + d;
    cam.pitch.to = c.pitch; cam.zoom.to = c.zoom; cam.cx.to = c.cx; cam.cy.to = c.cy;
    M.kick();
  }

  // MARK: - The model

  var rooms = {}, roomList = [], walls = {}, wallList = [], items = [], flats = [], fixtures = [], fans = [];
  var strips = [], shadows = [];

  function setup() {
    fitOverview();
    var i, k;
    for (i = 0; i < H.rooms.length; i++) {
      var r = H.rooms[i], c = r.camera;
      rooms[r.id] = {def: r, glow: Spring(PR.ROOM_FADE, 0), emph: Spring(PR.ROOM_FADE, 1),
        camera: c ? {yaw: c.yaw, pitch: c.pitch, zoom: c.zoom, cx: c.center[0], cy: c.center[1]} : null};
      roomList.push(rooms[r.id]);
    }
    // Walls: thin boxes cut into 1 m pieces (each sorts by its own depth), overlapping 2 cm so no seam shows.
    for (i = 0; i < H.walls.length; i++) {
      // An outside wall's height follows a WALL_CUT spring: 1 standing, 0 lowered toward the camera.
      var w = H.walls[i], wall = {def: w, h: w.h, tall: 1, cut: Spring(PR.WALL_CUT, 1)};
      if (w.id) walls[w.id] = wall;
      wallList.push(wall);
      var x0 = w.from[0], y0 = w.from[1], x1 = w.to[0], y1 = w.to[1], along = y0 === y1;
      var len = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)), n = Math.max(1, Math.ceil(len)), t2 = 0.12;
      for (k = 0; k < n; k++) {
        var e = k < n - 1 ? 0.02 : 0, ax = x0 + (x1 - x0) * k / n, ay = y0 + (y1 - y0) * k / n;
        var bx = x0 + (x1 - x0) * (k + 1) / n + (x1 > x0 ? e : 0), by = y0 + (y1 - y0) * (k + 1) / n + (y1 > y0 ? e : 0);
        var b = along ? [Math.min(ax, bx), ay - t2 / 2, 0, Math.abs(bx - ax), t2, w.h] : [ax - t2 / 2, Math.min(ay, by), 0, t2, Math.abs(by - ay), w.h];
        items.push({box: b, rooms: roomsAt(b[0] + b[3] / 2, b[1] + b[4] / 2), wall: wall, kind: 'wall'});
        // A darker band on the floor where the wall meets it, on each side that's indoors.
        for (var side = -1; side <= 1; side += 2) {
          var sx = along ? 0 : side, sy = along ? side : 0;
          var inRooms = roomsAt(b[0] + b[3] / 2 + sx * 0.2, b[1] + b[4] / 2 + sy * 0.2);
          if (inRooms.length) strips.push({b: b, sx: sx, sy: sy, rooms: inRooms});
        }
      }
    }
    for (i = 0; i < H.furniture.length; i++) {
      var f = H.furniture[i], o = f.box;
      var it = {box: o, color: f.color, rooms: [f.room], kind: f.kind, round: f.shape === 'round', alpha: f.alpha || 1,
        disc: f.shape === 'disc' ? f.face : null,
        wall: f.wall ? walls[f.wall] : null, cx: o[0] + o[3] / 2, cy: o[1] + o[4] / 2,
        screen: f.color === 'screen' && Math.max(o[3], o[4]) > 0.3, phase: (o[0] * 0.37 + o[1] * 0.11) % 1};
      if (o[5] <= 0.02 && o[2] <= 0.03) { flats.push(it); continue; }
      items.push(it);
      if (f.shadow !== false && o[2] < 0.3) shadows.push(it);
    }
    flats.sort(function (a, b2) { return a.box[2] - b2.box[2]; });
    // Something resting on another box (a duvet on a bed, a monitor on its stand) is drawn after it.
    for (i = 0; i < items.length; i++) {
      var top = items[i].box;
      if (top[2] <= 0) continue;
      for (k = 0; k < items.length; k++) {
        var un = items[k].box;
        if (k === i || Math.abs(un[2] + un[5] - top[2]) > 0.03) continue;
        if (top[0] < un[0] + un[3] && un[0] < top[0] + top[3] && top[1] < un[1] + un[4] && un[1] < top[1] + top[4]) {
          (items[i].under || (items[i].under = [])).push(items[k]);
        }
      }
    }
    for (i = 0; i < H.fixtures.length; i++) {
      var d = H.fixtures[i], at = d.at, lampH = d.height || 1.3;
      var fx = {def: d, kind: d.kind, on: false, grow: Spring(PR.SNAPPY_FOCUS, 0), lit: Spring(PR.ROOM_FADE, 0),
        flash: Spring(PR.IGNITE, 0), wave: 0, ripple: -1, glowAt: d.glow || at, rooms: [d.room], fixture: true,
        nudgeX: Spring(PR.BOUNDARY_BUMP, 0).px(), nudgeY: Spring(PR.BOUNDARY_BUMP, 0).px(),
        mid: d.kind === 'lamp' ? lampH + 0.22 : at[2], top: d.kind === 'lamp' ? lampH + 0.45 : at[2] + 0.06};
      fixtures.push(fx);
      items.push(fx);
    }
    for (i = 0; i < (H.fans || []).length; i++) {
      var fan = {def: H.fans[i], fan: true, rooms: [H.fans[i].room]};
      fans.push(fan);
      items.push(fan);
    }
    // The overview's screen positions steer the arrows, so they stay predictable while the camera moves.
    aim(OVER.yaw, OVER.pitch, 1, OVER.cx, OVER.cy);
    for (i = 0; i < fixtures.length; i++) {
      var p = pt(fixtures[i].def.at[0], fixtures[i].def.at[1], fixtures[i].mid);
      fixtures[i].nx = p[0]; fixtures[i].ny = p[1];
    }
    cam.cx.x = cam.cx.to = OVER.cx; cam.cy.x = cam.cy.to = OVER.cy;
  }

  function roomsAt(x, y) {
    var out = [];
    for (var i = 0; i < H.rooms.length; i++) {
      var r = H.rooms[i].rect;
      if (x >= r[0] - 0.2 && x <= r[2] + 0.2 && y >= r[1] - 0.2 && y <= r[3] + 0.2) out.push(H.rooms[i].id);
    }
    return out;
  }

  // MARK: - Colour

  function hex(c) { return [parseInt(c.substr(1, 2), 16), parseInt(c.substr(3, 2), 16), parseInt(c.substr(5, 2), 16)]; }
  var PAL = {cool: {}, warm: {}};
  (function () {
    for (var k in H.palette.cool) if (H.palette.cool.hasOwnProperty(k)) { PAL.cool[k] = hex(H.palette.cool[k]); PAL.warm[k] = hex(H.palette.warm[k] || H.palette.cool[k]); }
  })();
  var GLOW = hex(H.palette.glow);

  /** A colour: cool to warm by g, shaded, and faded toward grey when its room isn't the one in focus. */
  function color(role, g, shade, emph, alpha) {
    var a = PAL.cool[role] || [150, 150, 160], b = PAL.warm[role] || a, c = [0, 0, 0], i;
    for (i = 0; i < 3; i++) c[i] = (a[i] + (b[i] - a[i]) * g) * shade;
    if (emph < 0.999) {
      // Another room than the focused one: 30% less colour, and 45% of it left over the dark background.
      var lum = 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2], keep = 1 - DESAT * (1 - emph), left = DIM + (1 - DIM) * emph;
      for (i = 0; i < 3; i++) c[i] = BG[i] + (lum + (c[i] - lum) * keep - BG[i]) * left;
    }
    var s = Math.round(Math.max(0, Math.min(255, c[0]))) + ',' + Math.round(Math.max(0, Math.min(255, c[1]))) + ',' + Math.round(Math.max(0, Math.min(255, c[2])));
    return alpha !== undefined && alpha < 1 ? 'rgba(' + s + ',' + alpha + ')' : 'rgb(' + s + ')';
  }

  function emphOf(ids) {
    var e = 0;
    for (var i = 0; i < ids.length; i++) if (rooms[ids[i]]) e = Math.max(e, rooms[ids[i]].emph.x);
    return ids.length ? e : 1;
  }

  function maxGlow(ids) {
    var g = 0;
    for (var i = 0; i < ids.length; i++) if (rooms[ids[i]]) g = Math.max(g, rooms[ids[i]].glow.x);
    return g;
  }

  /** How far a light's warmth has reached (0 to 1) at a distance d: the wave front softened over 0.8 m. */
  function reach(f, d) {
    var t = Math.max(0, Math.min(1, (f.wave - d) / 0.8 + 0.5));
    return t * t * (3 - 2 * t);
  }

  /** How warm a thing is: its room's light, more the nearer it is to a lit light, once the light's wave reached it. */
  function warmth(it) {
    var g = 0, i;
    var cx = it.cx !== undefined ? it.cx : it.box[0] + it.box[3] / 2, cy = it.cy !== undefined ? it.cy : it.box[1] + it.box[4] / 2;
    for (i = 0; i < fixtures.length; i++) {
      var f = fixtures[i];
      if (f.lit.x < 0.01 || it.rooms.indexOf(f.def.room) < 0) continue;
      var dx = cx - f.glowAt[0], dy = cy - f.glowAt[1], d = Math.sqrt(dx * dx + dy * dy), r = reach(f, d);
      g += f.lit.x * r * (0.4 + Math.max(0, 1 - d / 4.2) * 0.8);
    }
    return Math.min(1, g);
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

  /** A box: the vertical faces turned toward the camera, then its top with a light edge (clay). */
  function drawBox(o, role, g, e, topRole, alpha) {
    var x = o[0], y = o[1], z = o[2], w = o[3], d = o[4], h = o[5], a = alpha === undefined ? 1 : alpha;
    if (h <= 0.001 || a < 0.01) return;
    sides(x, y, z, w, d, h, role, g, e, a);
    var top = [pt(x, y, z + h), pt(x + w, y, z + h), pt(x + w, y + d, z + h), pt(x, y + d, z + h)];
    poly(top, color(topRole || role, g, topRole ? 1 : 1.05, e, a));
    if (!M.lite && h > 0.03 && w > 0.08 && d > 0.08) {
      ctx.strokeStyle = 'rgba(255,255,255,' + (0.3 * a * (0.4 + 0.6 * e)) + ')';
      ctx.lineWidth = 1;
      ctx.stroke();
    }
  }

  /** A box's vertical faces that look at the camera. */
  function sides(x, y, z, w, d, h, role, g, e, a) {
    if (P.cos > 0.001) poly([pt(x, y + d, z), pt(x + w, y + d, z), pt(x + w, y + d, z + h), pt(x, y + d, z + h)], color(role, g, 0.93, e, a));
    if (P.cos < -0.001) poly([pt(x + w, y, z), pt(x, y, z), pt(x, y, z + h), pt(x + w, y, z + h)], color(role, g, 0.93, e, a));
    if (P.sin > 0.001) poly([pt(x + w, y, z), pt(x + w, y + d, z), pt(x + w, y + d, z + h), pt(x + w, y, z + h)], color(role, g, 0.83, e, a));
    if (P.sin < -0.001) poly([pt(x, y + d, z), pt(x, y, z), pt(x, y, z + h), pt(x, y + d, z + h)], color(role, g, 0.83, e, a));
  }

  /** A flat circle on one side of something (a washer's door, a dial): only when that side faces the camera. */
  function drawDisc(o, face, role, g, e, a) {
    var axis = face.charAt(1), sign = face.charAt(0) === '+' ? 1 : -1;
    if ((axis === 'y' ? P.cos : P.sin) * sign <= 0.001) return;
    var r = (axis === 'y' ? o[3] : o[4]) / 2, cx = o[0] + o[3] / 2, cy = o[1] + o[4] / 2, cz = o[2] + o[5] / 2, pts = [];
    for (var i = 0; i < 20; i++) {
      var t = i / 20 * 2 * Math.PI, u = r * Math.cos(t), v = r * Math.sin(t);
      pts.push(axis === 'y' ? pt(cx + u, cy, cz + v) : pt(cx, cy + u, cz + v));
    }
    poly(pts, color(role, g, axis === 'y' ? 0.97 : 0.88, e, a));
  }

  /** A round thing (a pot, a stool, a basket): a circle seen at the camera's pitch is an upright ellipse here. */
  function drawRound(o, role, g, e, alpha) {
    var r = Math.min(o[3], o[4]) / 2, cx = o[0] + o[3] / 2, cy = o[1] + o[4] / 2, s = P.s;
    var b = pt(cx, cy, o[2]), t = pt(cx, cy, o[2] + o[5]), rx = r * s, ry = r * s * P.sp, a = alpha === undefined ? 1 : alpha;
    ctx.fillStyle = color(role, g, 0.86, e, a);
    ctx.beginPath();
    ctx.moveTo(b[0] - rx, b[1]);
    ctx.lineTo(t[0] - rx, t[1]);
    ctx.lineTo(t[0] + rx, t[1]);
    ctx.lineTo(b[0] + rx, b[1]);
    ctx.closePath();
    ctx.fill();
    ellipse(b[0], b[1], rx, ry);
    ctx.fill();
    ellipse(t[0], t[1], rx, ry);
    ctx.fillStyle = color(role, g, 1.05, e, a);
    ctx.fill();
  }

  function ellipse(x, y, rx, ry) {
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(1, ry / Math.max(0.001, rx));
    ctx.beginPath();
    ctx.arc(0, 0, rx, 0, 2 * Math.PI);
    ctx.restore();
  }

  function quadAt(x0, y0, x1, y1, z) {
    return [pt(x0, y0, z), pt(x1, y0, z), pt(x1, y1, z), pt(x0, y1, z)];
  }

  /** Soft contact shadows under everything standing on the floor, two layers. */
  function drawShadows() {
    for (var i = 0; i < shadows.length; i++) {
      var o = shadows[i].box, e = emphOf(shadows[i].rooms);
      for (var k = 0; k < 2; k++) {
        var grow = k ? 0.16 : 0.05, fill = 'rgba(40,32,26,' + ((k ? 0.07 : 0.13) * (0.5 + 0.5 * e)) + ')';
        if (shadows[i].round) {
          var c = pt(o[0] + o[3] / 2, o[1] + o[4] / 2, 0.002), r = (Math.min(o[3], o[4]) / 2 + grow) * P.s;
          ellipse(c[0], c[1], r, r * P.sp);
          ctx.fillStyle = fill;
          ctx.fill();
        } else {
          poly(quadAt(o[0] - grow, o[1] - grow, o[0] + o[3] + grow, o[1] + o[4] + grow, 0.002), fill);
        }
      }
    }
  }

  /** Ambient occlusion: two darker bands on the floor along each wall. */
  function drawStrips() {
    for (var i = 0; i < strips.length; i++) {
      var s = strips[i], b = s.b, e = emphOf(s.rooms);
      for (var k = 0; k < 2; k++) {
        var wd = k ? 0.32 : 0.12, alpha = (k ? 0.06 : 0.12) * (0.5 + 0.5 * e), x0, y0, x1, y1;
        if (s.sy) { x0 = b[0]; x1 = b[0] + b[3]; y0 = s.sy > 0 ? b[1] + b[4] : b[1] - wd; y1 = s.sy > 0 ? b[1] + b[4] + wd : b[1]; }
        else { y0 = b[1]; y1 = b[1] + b[4]; x0 = s.sx > 0 ? b[0] + b[3] : b[0] - wd; x1 = s.sx > 0 ? b[0] + b[3] + wd : b[0]; }
        poly(quadAt(x0, y0, x1, y1, 0.001), 'rgba(60,44,30,' + alpha + ')');
      }
    }
  }

  function floorGlow(f) {
    var g = f.lit.x * (1 + Math.max(0, f.flash.x));
    if (g < 0.01 || f.wave < 0.05) return;
    var r = rooms[f.def.room].def.rect, c = pt(f.glowAt[0], f.glowAt[1], 0);
    ctx.save();
    var q = quadAt(r[0], r[1], r[2], r[3], 0);
    ctx.beginPath();
    ctx.moveTo(q[0][0], q[0][1]);
    for (var i = 1; i < 4; i++) ctx.lineTo(q[i][0], q[i][1]);
    ctx.closePath();
    ctx.clip();
    // An ellipse on the floor: 3 m across, squashed by the pitch.
    ctx.translate(c[0], c[1]);
    ctx.scale(1, P.sp);
    var R = Math.min(3, f.wave + 0.4) * P.s, grad = ctx.createRadialGradient(0, 0, 0, 0, 0, R);
    grad.addColorStop(0, 'rgba(' + GLOW[0] + ',' + GLOW[1] + ',' + GLOW[2] + ',' + Math.min(0.6, 0.32 * g) + ')');
    grad.addColorStop(1, 'rgba(' + GLOW[0] + ',' + GLOW[1] + ',' + GLOW[2] + ',0)');
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = grad;
    ctx.fillRect(-R, -R, 2 * R, 2 * R);
    ctx.restore();
  }

  function mix(a, b, t) {
    return 'rgb(' + Math.round(a[0] + (b[0] - a[0]) * t) + ',' + Math.round(a[1] + (b[1] - a[1]) * t) + ',' + Math.round(a[2] + (b[2] - a[2]) * t) + ')';
  }

  function bloom(x, y, R, g) {
    var grad = ctx.createRadialGradient(x, y, 0, x, y, R);
    grad.addColorStop(0, 'rgba(255,200,130,' + Math.min(0.8, 0.55 * g) + ')');
    grad.addColorStop(1, 'rgba(255,192,122,0)');
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = grad;
    ctx.fillRect(x - R, y - R, 2 * R, 2 * R);
    ctx.restore();
  }

  function drawFixture(f) {
    var d = f.def, at = d.at, g = f.lit.x, boost = 1 + Math.max(0, f.flash.x), grow = 1 + 0.12 * f.grow.x, s = P.s, e = emphOf(f.rooms);
    var c = pt(at[0], at[1], f.mid);
    ctx.save();
    ctx.translate(c[0], c[1]);
    ctx.scale(grow, grow);
    ctx.translate(-c[0], -c[1]);
    if (d.kind === 'tube') {
      tube(f, g * boost, e);
    } else if (d.kind === 'lamp') {
      // The lamp: a base, a pole, a shade that glows, and a soft cone of light under it when it's on.
      var h = d.height || 1.3, base = pt(at[0], at[1], 0), shadeTop = pt(at[0], at[1], h + 0.45), shadeBottom = pt(at[0], at[1], h);
      if (g > 0.01) cone(base, shadeBottom, s, g * boost);
      ctx.fillStyle = color('wood', g, 0.8, e);
      ellipse(base[0], base[1], 0.18 * s, 0.18 * s * P.sp);
      ctx.fill();
      ctx.strokeStyle = color('wood', g, 0.7, e);
      ctx.lineWidth = Math.max(1.5, 0.035 * s);
      ctx.beginPath(); ctx.moveTo(base[0], base[1]); ctx.lineTo(shadeBottom[0], shadeBottom[1]); ctx.stroke();
      if (g > 0.01) bloom(c[0], c[1], 0.9 * s * boost, g * boost);
      var rt = 0.15 * s, rb = 0.24 * s;
      ctx.beginPath();
      ctx.moveTo(shadeTop[0] - rt, shadeTop[1]);
      ctx.lineTo(shadeTop[0] + rt, shadeTop[1]);
      ctx.lineTo(shadeBottom[0] + rb, shadeBottom[1]);
      ctx.lineTo(shadeBottom[0] - rb, shadeBottom[1]);
      ctx.closePath();
      ctx.fillStyle = g > 0.01 ? mix(PAL.cool.linen, [255, 226, 180], Math.min(1, g)) : color('linen', 0, 0.95, e);
      ctx.fill();
    } else {
      bulb(c[0], c[1], 0.16 * s, g * boost);
    }
    ctx.restore();
  }

  /** The light falling from a lamp's shade to the floor: a trapezoid fading downward, added light. */
  function cone(base, shadeBottom, s, g) {
    var top = 0.22 * s, bottom = 0.95 * s, grad = ctx.createLinearGradient(0, shadeBottom[1], 0, base[1]);
    grad.addColorStop(0, 'rgba(255,205,140,' + Math.min(0.5, 0.26 * g) + ')');
    grad.addColorStop(1, 'rgba(255,205,140,0)');
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.moveTo(shadeBottom[0] - top, shadeBottom[1]);
    ctx.lineTo(shadeBottom[0] + top, shadeBottom[1]);
    ctx.lineTo(base[0] + bottom, base[1]);
    ctx.lineTo(base[0] - bottom, base[1]);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  }

  /** A wall tube light: a bar with dark end caps; lit, a warm core and a wash on its wall. */
  function tube(f, g, e) {
    var d = f.def, at = d.at, half = (d.length || 1.2) / 2, s = P.s;
    var ax = d.along === 'x' ? at[0] - half : at[0], ay = d.along === 'x' ? at[1] : at[1] - half;
    var bx = d.along === 'x' ? at[0] + half : at[0], by = d.along === 'x' ? at[1] : at[1] + half;
    var a = pt(ax, ay, at[2]), b = pt(bx, by, at[2]), c = pt(at[0], at[1], at[2]);
    if (g > 0.01) bloom(c[0], c[1], 1.2 * s, g);
    ctx.lineCap = 'round';
    ctx.strokeStyle = color('charcoal', 0, 0.9, e);
    ctx.lineWidth = Math.max(3, 0.11 * s);
    ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
    var a2 = [a[0] + (b[0] - a[0]) * 0.09, a[1] + (b[1] - a[1]) * 0.09], b2 = [a[0] + (b[0] - a[0]) * 0.91, a[1] + (b[1] - a[1]) * 0.91];
    ctx.strokeStyle = g > 0.01 ? mix([214, 218, 226], [255, 236, 204], Math.min(1, g)) : color('white', 0, 0.92, e);
    ctx.lineWidth = Math.max(2, 0.08 * s);
    ctx.beginPath(); ctx.moveTo(a2[0], a2[1]); ctx.lineTo(b2[0], b2[1]); ctx.stroke();
    if (g > 0.01) {
      ctx.strokeStyle = 'rgba(255,250,240,' + Math.min(1, g) + ')';
      ctx.lineWidth = Math.max(1, 0.035 * s);
      ctx.beginPath(); ctx.moveTo(a2[0], a2[1]); ctx.lineTo(b2[0], b2[1]); ctx.stroke();
    }
    ctx.lineCap = 'butt';
  }

  function bulb(x, y, r, g) {
    if (g > 0.01) bloom(x, y, r * 6, g);
    var grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, 0, x, y, r);
    grad.addColorStop(0, mix([246, 248, 252], [255, 251, 238], Math.min(1, g)));
    grad.addColorStop(1, mix([176, 182, 194], [255, 196, 120], Math.min(1, g)));
    ctx.fillStyle = grad;
    ctx.beginPath(); ctx.arc(x, y, r, 0, 2 * Math.PI); ctx.fill();
  }

  /** A standing fan's cage and its three blades, turning in the plane it faces (the motor behind is a box). */
  function drawFan(fan) {
    var d = fan.def, c = d.center, r = d.r, e = emphOf(fan.rooms), alongY = d.face.charAt(1) === 'y';
    var turn = still() ? 0.3 : (clock / 1000 / FAN_PERIOD) % 1 * 2 * Math.PI, i, k;
    function at(u, v) { return alongY ? pt(c[0] + u, c[1], c[2] + v) : pt(c[0], c[1] + u, c[2] + v); }
    for (i = 0; i < 3; i++) {
      var a = turn + i * 2 * Math.PI / 3, pts = [], radii = [0.035, r * 0.92, r * 0.92, 0.035], spread = [0.32, 0.22, -0.22, -0.32];
      for (k = 0; k < 4; k++) pts.push(at(radii[k] * Math.cos(a + spread[k]), radii[k] * Math.sin(a + spread[k])));
      poly(pts, color('silver', 0, 0.95, e, 0.85));
    }
    var hub = [];
    for (i = 0; i < 12; i++) hub.push(at(0.04 * Math.cos(i * Math.PI / 6), 0.04 * Math.sin(i * Math.PI / 6)));
    poly(hub, color('charcoal', 0, 1, e));
    // The cage: a thin ring.
    ctx.beginPath();
    for (i = 0; i <= 24; i++) {
      var p = at(r * Math.cos(i * Math.PI / 12), r * Math.sin(i * Math.PI / 12));
      if (i) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]);
    }
    ctx.strokeStyle = color('charcoal', 0, 1.1, e);
    ctx.lineWidth = Math.max(1, 0.012 * P.s);
    ctx.stroke();
  }

  /** A screen's standby glint: a faint diagonal band sweeping its face once every GLINT_PERIOD s. */
  function glint(t, e) {
    var o = t.box, p = (clock / 1000 / GLINT_PERIOD + t.phase) % 1, q = p * 5;
    if (q >= 1) return;
    var alongY = o[3] >= o[4], x = o[0], y = o[1], z = o[2], w = alongY ? o[3] : o[4], h = o[5];
    // The face toward the camera.
    var front = alongY ? (P.cos > 0 ? y + o[4] + 0.002 : y - 0.002) : (P.sin > 0 ? x + o[3] + 0.002 : x - 0.002);
    function at(u, v) { return alongY ? pt(x + u, front, z + v) : pt(front, y + u, z + v); }
    var c = -0.3 * w + q * 1.6 * w, band = 0.12 * w, lean = 0.35 * h;
    var a = 0.16 * Math.sin(q * Math.PI) * (0.4 + 0.6 * e);
    var u0 = Math.max(0, c - lean), u1 = Math.min(w, c + band - lean), u2 = Math.min(w, c + band), u3 = Math.max(0, c);
    if (u2 <= 0 || u0 >= w) return;
    poly([at(u0, 0), at(u1, 0), at(u2, h), at(u3, h)], 'rgba(255,255,255,' + a + ')');
  }

  function followsWall(k) { return k === 'door-glass' || k === 'door-frame' || k === 'door'; }

  /** A thing's box this frame: doors on an outside wall follow that wall down when it lowers. */
  function boxNow(it) {
    var o = it.box, wl = it.wall;
    if (!wl || !followsWall(it.kind)) return o;
    var s = wl.h / wl.def.h;
    return [o[0], o[1], o[2] * s, o[3], o[4], o[5] * s];
  }

  /** Art, hooks and cabinets on an outside wall are gone while their wall is down. */
  function alphaNow(it) {
    return it.wall && !followsWall(it.kind) ? it.alpha * it.wall.tall : it.alpha;
  }

  function draw() {
    ctx.clearRect(0, 0, 1920, 1080);
    var i;
    // A soft shadow under the house, so it sits on the frosted background.
    var c0 = pt(W / 2, D / 2, 0), R = 0.62 * Math.max(W, D) * P.s, sh = ctx.createRadialGradient(0, 0, 0, 0, 0, 1);
    sh.addColorStop(0, 'rgba(0,0,0,0.34)');
    sh.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.save();
    ctx.translate(c0[0], c0[1] + 0.4 * P.s);
    ctx.scale(R, R * P.sp);
    ctx.fillStyle = sh;
    ctx.fillRect(-1, -1, 2, 2);
    ctx.restore();
    // Each room's base (the plan isn't a rectangle), back to front, then the floors, rugs, shading and light pools.
    var slabs = roomList.slice().sort(function (a, b) {
      var ra = a.def.rect, rb = b.def.rect;
      return depth((ra[0] + ra[2]) / 2, (ra[1] + ra[3]) / 2, 0) - depth((rb[0] + rb[2]) / 2, (rb[1] + rb[3]) / 2, 0);
    });
    for (i = 0; i < slabs.length; i++) {
      var rr = slabs[i].def.rect;
      drawBox([rr[0] - 0.1, rr[1] - 0.1, -0.25, rr[2] - rr[0] + 0.2, rr[3] - rr[1] + 0.2, 0.25], 'wall', 0, slabs[i].emph.x, 'wall-top');
    }
    for (i = 0; i < roomList.length; i++) {
      var room = roomList[i], r = room.def.rect;
      poly(quadAt(r[0], r[1], r[2], r[3], 0), color('floor-' + room.def.floor, 0.35 * room.glow.x, 1, room.emph.x));
    }
    if (!M.lite) drawStrips();
    for (i = 0; i < flats.length; i++) drawBox(flats[i].box, flats[i].color, warmth(flats[i]), emphOf(flats[i].rooms));
    if (!M.lite) drawShadows();
    for (i = 0; i < fixtures.length; i++) floorGlow(fixtures[i]);
    // Everything that stands, back to front.
    for (i = 0; i < items.length; i++) {
      var it = items[i];
      if (it.fixture) {
        var at = it.def.at;
        it.depth = depth(at[0] + (it.kind === 'tube' ? 0.12 : 0), at[1], it.kind === 'tube' ? at[2] : 0.4);
      } else if (it.fan) {
        var fc = it.def.center;
        it.depth = depth(fc[0], fc[1], 0.3);
      } else {
        var o = it.box;
        it.depth = depth(o[0] + o[3] / 2, o[1] + o[4] / 2, o[2]);
      }
    }
    for (i = 0; i < items.length; i++) {
      var under = items[i].under;
      if (under) for (var u = 0; u < under.length; u++) items[i].depth = Math.max(items[i].depth, under[u].depth + 0.001);
    }
    items.sort(function (a, b) { return a.depth - b.depth; });
    for (i = 0; i < items.length; i++) {
      var t = items[i];
      if (t.fixture) { drawFixture(t); continue; }
      if (t.fan) { drawFan(t); continue; }
      if (t.kind === 'wall') {
        var b = t.box;
        var wg = 0.4 * maxGlow(t.rooms), we = emphOf(t.rooms);
        drawBox([b[0], b[1], 0, b[3], b[4], t.wall.h], 'wall', wg, we, 'wall-top');
        sides(b[0], b[1], 0, b[3], b[4], Math.min(0.09, t.wall.h), 'baseboard', wg, we, 1);   // the baseboards
        continue;
      }
      var box = boxNow(t), a = alphaNow(t), g = warmth(t), e = emphOf(t.rooms);
      if (t.disc) drawDisc(box, t.disc, t.color, g, e, a);
      else if (t.round) drawRound(box, t.color, g, e, a);
      else drawBox(box, t.color, g, e, undefined, a);
      if (t.screen && !still()) glint(t, e);
    }
    // The model fades out under the tab bar and the status pill, as content does under a tvOS bar.
    ctx.save();
    ctx.globalCompositeOperation = 'destination-out';
    var fade = ctx.createLinearGradient(0, 0, 0, 200);
    fade.addColorStop(0, 'rgba(0,0,0,1)');
    fade.addColorStop(0.55, 'rgba(0,0,0,0.6)');
    fade.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = fade;
    ctx.fillRect(0, 0, 1920, 200);
    ctx.restore();
    overlay();
  }

  // MARK: - The dots and the pill (drawn last, above everything)

  function overlay() {
    for (var i = 0; i < fixtures.length; i++) {
      var f = fixtures[i], at = f.def.at, fo = f.grow.x, g = f.lit.x, base = pt(at[0], at[1], f.top), p = pt(at[0], at[1], f.top + 0.42);
      // The focused dot grows 1.35× and rides 8 px higher on a longer stem; a dead end nudges it (the rubber band).
      var x = p[0] + M.rubber(f.nudgeX.x), y = p[1] - 8 * fo + M.rubber(f.nudgeY.x);
      var breath = g > 0.5 && !still() ? 1 + 0.04 * Math.sin(clock / 1000 * 2 * Math.PI / 4) * g : 1;
      var r = 8 * (1 + 0.35 * fo) * breath;
      // The hairline stem from the light up to its dot.
      ctx.strokeStyle = 'rgba(255,255,255,' + (0.45 + 0.4 * fo) + ')';
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(base[0], base[1]); ctx.lineTo(x, y + r); ctx.stroke();
      if (g > 0.01) {
        var halo = ctx.createRadialGradient(x, y, 0, x, y, r * 3.2);
        halo.addColorStop(0, 'rgba(255,192,122,' + (0.5 * g) + ')');
        halo.addColorStop(1, 'rgba(255,192,122,0)');
        ctx.fillStyle = halo;
        ctx.fillRect(x - r * 3.2, y - r * 3.2, r * 6.4, r * 6.4);
      }
      ctx.beginPath(); ctx.arc(x, y, r, 0, 2 * Math.PI);
      ctx.fillStyle = g > 0.5 ? 'rgb(255,192,122)' : 'rgba(30,32,38,0.72)';
      ctx.fill();
      ctx.lineWidth = 2;
      ctx.strokeStyle = g > 0.5 ? 'rgba(255,240,220,0.95)' : 'rgba(255,255,255,0.8)';
      ctx.stroke();
      if (fo > 0.01) {
        ctx.beginPath(); ctx.arc(x, y, r + 6, 0, 2 * Math.PI);
        ctx.lineWidth = 3;
        ctx.strokeStyle = 'rgba(255,255,255,' + Math.min(1, fo) + ')';
        ctx.stroke();
      }
      if (f.ripple >= 0) {
        // OK: a ring leaves the dot, 1 → 2.8× its size, fading from 0.8.
        ctx.beginPath(); ctx.arc(x, y, r * (1 + 1.8 * f.ripple), 0, 2 * Math.PI);
        ctx.lineWidth = 2;
        ctx.strokeStyle = 'rgba(255,255,255,' + (0.8 * (1 - f.ripple)) + ')';
        ctx.stroke();
      }
      f.dotX = x; f.dotY = y; f.dotR = r;
    }
  }

  // The pill follows the focused dot on its own spring and stays inside the safe area. Two copies: moving to another
  // light, the old text fades out in one while the new fades in in the other, both riding the same spring.
  var pillX = Spring(PR.LIQUID_PILL, 960).px(), pillY = Spring(PR.LIQUID_PILL, 540).px(), pillShown = false;

  function placePill(snap) {
    var f = fixtures[focus];
    if (!f || f.dotX === undefined) return false;
    var cur = pills[pillAt], w = cur.offsetWidth || 300, h = cur.offsetHeight || 52;
    var x = Math.max(SAFE.left + w / 2, Math.min(SAFE.right - w / 2, f.dotX));
    var y = Math.max(SAFE.top, Math.min(SAFE.bottom - h, f.dotY - f.dotR - 16 - h));
    pillX.to = x; pillY.to = y;
    if (snap) { pillX.snap(x); pillY.snap(y); }
    for (var i = 0; i < pills.length; i++) {
      var pw = pills[i].offsetWidth || w;
      M.transform(pills[i], 'translate(' + Math.round(pillX.x - pw / 2) + 'px,' + Math.round(pillY.x) + 'px)');
    }
    return true;
  }

  function showCaption() {
    var f = fixtures[focus];
    if (!focused || !f) {
      for (var i = 0; i < pills.length; i++) pills[i].className = 'pill';
      pillShown = false;
      pillText = '';
      return;
    }
    var nameText = roomName(f.def.room) + '  ·  ' + name(f.def.name), stateText = '  ·  ' + (f.on ? tr('On') : tr('Off'));
    // The same light (its state changed): the text changes in place; another light: crossfade to the other copy.
    var same = pillText.split('|')[0] === nameText;
    if (pillText && !same) {
      pills[pillAt].className = 'pill';
      pillAt = 1 - pillAt;
    }
    var cur = pills[pillAt];
    cur.querySelector('.n').textContent = nameText;
    cur.querySelector('.s').textContent = stateText;
    cur.className = 'pill on' + (f.on ? ' lit' : '');
    pillText = nameText + '|' + stateText;
    if (!pillShown) placePill(true);
    pillShown = true;
    M.kick();
  }

  // MARK: - Animation

  function cameraTarget() {
    var f = fixtures[focus], room = focused && f ? rooms[f.def.room] : null;
    target(room && room.camera ? room.camera : OVER);
    for (var i = 0; i < roomList.length; i++) roomList[i].emph.to = !focused || !room || roomList[i] === room ? 1 : 0;
  }

  /** One frame (motion.js's loop): every spring a step, then the drawing. True while anything still moves. */
  function tick(dt, now) {
    if (!shown || !canvas) return false;
    if (dt > 0.1) dt = 0.1;
    clock = now;
    var moving = false, i, k;
    for (k in cam) if (cam.hasOwnProperty(k) && cam[k].step(dt)) moving = true;
    for (i = 0; i < fixtures.length; i++) {
      var f = fixtures[i];
      f.grow.to = focused && i === focus ? 1 : 0;
      f.lit.to = f.on ? 1 : 0;
      if (f.grow.step(dt)) moving = true;
      if (f.lit.step(dt)) moving = true;
      if (f.flash.step(dt)) moving = true;
      if (f.nudgeX.step(dt)) moving = true;
      if (f.nudgeY.step(dt)) moving = true;
      // The warmth's reach: out at WAVE_SPEED when on, back into the light in WAVE_OUT s when off.
      if (f.on && f.wave < WAVE_MAX) { f.wave = still() ? WAVE_MAX : Math.min(WAVE_MAX, f.wave + WAVE_SPEED * dt); moving = true; }
      if (!f.on && f.wave > 0) { f.wave = still() ? 0 : Math.max(0, f.wave - WAVE_MAX / WAVE_OUT * dt); moving = true; }
      if (f.ripple >= 0) { f.ripple += dt / 0.45; moving = true; if (f.ripple >= 1) f.ripple = -1; }
    }
    for (i = 0; i < roomList.length; i++) {
      var room = roomList[i], on = 0;
      for (k = 0; k < fixtures.length; k++) if (fixtures[k].def.room === room.def.id && fixtures[k].on) on = 1;
      room.glow.to = on;
      if (room.glow.step(dt)) moving = true;
      if (room.emph.step(dt)) moving = true;
    }
    // A slow figure eight once the remote has been still for IDLE ms (never with reduce motion), eased in and out.
    drift.to = !still() && !M.lite && Date.now() - lastKey > IDLE ? 1 : 0;
    if (drift.step(dt)) moving = true;
    var phase = now / 1000 * 2 * Math.PI / 36, dYaw = 1.5 * Math.sin(phase) * drift.x, dPitch = 0.6 * Math.sin(2 * phase) * drift.x;
    aim(cam.yaw.x + dYaw, cam.pitch.x + dPitch, cam.zoom.x, cam.cx.x, cam.cy.x);
    // Outside walls stand tall when the camera sees their inside, and lower themselves (WALL_CUT) when they face it.
    for (i = 0; i < wallList.length; i++) {
      var wl = wallList[i], o = wl.def.out;
      if (!o) continue;
      var v = -o[0] * P.sin - o[1] * P.cos, t = Math.max(0, Math.min(1, (v + 0.12) / 0.24));
      wl.cut.to = t * t * (3 - 2 * t);
      if (wl.cut.step(dt)) moving = true;
      wl.tall = Math.max(0, Math.min(1, wl.cut.x));
      wl.h = wl.def.low + (wl.def.h - wl.def.low) * wl.tall;
    }
    draw();
    if (pillShown && placePill(false)) {
      if (pillX.step(dt)) moving = true;
      if (pillY.step(dt)) moving = true;
    }
    // The fans turn, the screens glint, a lit dot breathes, the camera drifts: frames while the house shows.
    if (!still() && (fans.length || drift.x > 0.001)) moving = true;
    return moving;
  }

  /** A light on or off: on, the filament flashes (IGNITE) and the warmth starts spreading from the light. */
  function setOn(f, on) {
    f.on = on;
    if (on && !still()) { f.flash.snap(0); f.flash.impulse(FLASH); f.wave = 0; }
  }

  function roomName(id) {
    for (var i = 0; i < H.rooms.length; i++) if (H.rooms[i].id === id) return name(H.rooms[i].name);
    return id;
  }

  /** The nearest light in the pressed direction, measured in the overview so the arrows don't follow the camera. */
  function next(dx, dy) {
    var a = fixtures[focus], best = -1, bestScore = 1e9;
    for (var i = 0; i < fixtures.length; i++) {
      if (i === focus) continue;
      var ex = fixtures[i].nx - a.nx, ey = fixtures[i].ny - a.ny, along = dx ? ex * dx : ey * dy, across = Math.abs(dx ? ey : ex);
      if (along <= 4) continue;
      var score = along + 2 * across;
      if (score < bestScore) { bestScore = score; best = i; }
    }
    return best;
  }

  /** A dead end: the focused dot gets a push the way the arrow pointed and springs back (BOUNDARY_BUMP). */
  function bump(dx, dy) {
    var f = fixtures[focus];
    if (!f) return;
    if (dx) f.nudgeX.impulse(dx * M.BUMP_VELOCITY);
    if (dy) f.nudgeY.impulse(dy * M.BUMP_VELOCITY);
    M.kick();
  }

  function keyed() { lastKey = Date.now(); }

  window.House = {
    init: function (layer) {
      canvas = layer.querySelector('canvas');
      var first = layer.querySelector('.pill');
      pills = [first, first.cloneNode(true)];
      layer.appendChild(pills[1]);
      ctx = canvas.getContext('2d');
      setup();
      // The light nearest the middle of the overview gets the focus first.
      var bestD = 1e9;
      for (var i = 0; i < fixtures.length; i++) {
        var d = Math.abs(fixtures[i].nx - 960) + Math.abs(fixtures[i].ny - 560);
        if (d < bestD) { bestD = d; focus = i; }
      }
      aim(OVER.yaw, OVER.pitch, 1, OVER.cx, OVER.cy);
      draw();
      M.tick(tick);
    },
    /** The tab shows: the camera settles into the overview from a little higher and further (pitch +4°, zoom 0.94). */
    show: function (on) {
      if (on && !shown) {
        keyed();
        cam.yaw.snap(OVER.yaw); cam.pitch.snap(OVER.pitch + 4); cam.zoom.snap(0.94);
        cam.cx.snap(OVER.cx); cam.cy.snap(OVER.cy);
        cameraTarget();
      }
      shown = on;
      if (on) M.kick();
    },
    focus: function (on) { focused = on; keyed(); cameraTarget(); showCaption(); M.kick(); },
    /** false when there's no light that way and the press was ↑: the tab bar takes the focus. */
    move: function (dx, dy) {
      keyed();
      var n = next(dx, dy);
      if (n < 0) {
        if (dy < 0) return false;
        bump(dx, dy);
        return true;
      }
      focus = n;
      cameraTarget();
      showCaption();
      M.kick();
      return true;
    },
    toggle: function () {
      var f = fixtures[focus];
      if (!f) return;
      keyed();
      setOn(f, !f.on);
      f.ripple = 0;
      showCaption();
      M.kick();
    },
    /** For Welly's cards and the preview: a light by id, on or off. */
    set: function (id, on) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id && fixtures[i].on !== on) setOn(fixtures[i], on);
      showCaption();
      M.kick();
    },
    /** Moves the focus to a light by id (the screenshots, Welly's home cards). */
    select: function (id) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id) focus = i;
      cameraTarget();
      showCaption();
      M.kick();
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
