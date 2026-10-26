/*
 * The Home tab: the apartment as a clay diorama on one canvas (app/assets/house.json, written by tools/make_house.py;
 * the same drawing as the Android app's HouseView). Cool when the lights are off, warm around each light that's on.
 * The arrows move between the four lights (the TVs are scenery), OK switches the focused one. The camera swings to
 * the focused light's room on a spring; outside walls lower themselves when they'd stand between the camera and the
 * room. Each light has a dot above it; the focused one carries a pill with its name and state.
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

  var canvas, ctx, pill, pillName, pillState;
  var shown = false, focused = false, focus = 0, frame = 0, last = 0, lastKey = 0, lite = false, reduced = false;

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
    var near = Math.abs(this.x - this.to) < 0.0005 * Math.max(1, Math.abs(this.to)) && Math.abs(this.v) < 0.001;
    if (near) { this.x = this.to; this.v = 0; return false; }
    return true;
  };

  // MARK: - The camera

  // The camera swings on Apple's large-move spring; the overview keeps the blueprint's orientation.
  var cam = {yaw: new Spring(0.7, 0.9, H.camera.yaw), pitch: new Spring(0.7, 0.9, H.camera.pitch),
    zoom: new Spring(0.7, 0.9, 1), cx: new Spring(0.7, 0.9, W / 2), cy: new Spring(0.7, 0.9, D / 2)};
  var P = {cos: 1, sin: 0, sp: 1, cp: 0, s: 1, ox: 0, oy: 0};
  var S0 = 1, OVER = null, drift = 0;

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
  }

  // MARK: - The model

  var rooms = {}, roomList = [], walls = {}, wallList = [], items = [], flats = [], fixtures = [];
  var strips = [], shadows = [];

  function setup() {
    fitOverview();
    var i, k;
    for (i = 0; i < H.rooms.length; i++) {
      var r = H.rooms[i], c = r.camera;
      rooms[r.id] = {def: r, glow: new Spring(0.45, 0.9, 0), emph: new Spring(0.45, 0.9, 1),
        camera: c ? {yaw: c.yaw, pitch: c.pitch, zoom: c.zoom, cx: c.center[0], cy: c.center[1]} : null};
      roomList.push(rooms[r.id]);
    }
    // Walls: thin boxes cut into 1 m pieces (each sorts by its own depth), overlapping 2 cm so no seam shows.
    for (i = 0; i < H.walls.length; i++) {
      var w = H.walls[i], wall = {def: w, h: w.h, tall: 1};
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
        wall: f.wall ? walls[f.wall] : null, cx: o[0] + o[3] / 2, cy: o[1] + o[4] / 2};
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
      var fx = {def: d, kind: d.kind, on: false, grow: new Spring(0.3, 0.8, 0), lit: new Spring(0.45, 0.9, 0),
        ignite: 0, ripple: -1, glowAt: d.glow || at, rooms: [d.room], fixture: true,
        mid: d.kind === 'lamp' ? lampH + 0.22 : at[2], top: d.kind === 'lamp' ? lampH + 0.45 : at[2] + 0.06};
      fixtures.push(fx);
      items.push(fx);
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
      var lum = 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2], keep = 0.35 + 0.65 * emph, dim = 0.6 + 0.4 * emph;
      for (i = 0; i < 3; i++) c[i] = (lum + (c[i] - lum) * keep) * dim;
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

  /** How warm a thing is: its room's light, more the nearer it is to a lit light in that room. */
  function warmth(it) {
    var g = 0.4 * maxGlow(it.rooms), i;
    var cx = it.cx !== undefined ? it.cx : it.box[0] + it.box[3] / 2, cy = it.cy !== undefined ? it.cy : it.box[1] + it.box[4] / 2;
    for (i = 0; i < fixtures.length; i++) {
      var f = fixtures[i];
      if (f.lit.x < 0.01 || it.rooms.indexOf(f.def.room) < 0) continue;
      var dx = cx - f.glowAt[0], dy = cy - f.glowAt[1], d = Math.sqrt(dx * dx + dy * dy);
      g += f.lit.x * Math.max(0, 1 - d / 4.2) * 0.8;
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
    if (!lite && h > 0.03 && w > 0.08 && d > 0.08) {
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
    var g = f.lit.x * (1 + 0.5 * f.ignite);
    if (g < 0.01) return;
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
    var R = 3 * P.s, grad = ctx.createRadialGradient(0, 0, 0, 0, 0, R);
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
    var d = f.def, at = d.at, g = f.lit.x, boost = 1 + 0.6 * f.ignite, grow = 1 + 0.25 * f.grow.x, s = P.s, e = emphOf(f.rooms);
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
    if (!lite) drawStrips();
    for (i = 0; i < flats.length; i++) drawBox(flats[i].box, flats[i].color, warmth(flats[i]), emphOf(flats[i].rooms));
    if (!lite) drawShadows();
    for (i = 0; i < fixtures.length; i++) floorGlow(fixtures[i]);
    // Everything that stands, back to front.
    for (i = 0; i < items.length; i++) {
      var it = items[i];
      if (it.fixture) {
        var at = it.def.at;
        it.depth = depth(at[0] + (it.kind === 'tube' ? 0.12 : 0), at[1], it.kind === 'tube' ? at[2] : 0.4);
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
      var f = fixtures[i], at = f.def.at, base = pt(at[0], at[1], f.top), p = pt(at[0], at[1], f.top + 0.42);
      var g = f.lit.x, fo = f.grow.x, r = 8 * (1 + 0.45 * fo), x = p[0], y = p[1];
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
        ctx.strokeStyle = 'rgba(255,255,255,' + fo + ')';
        ctx.stroke();
      }
      if (f.ripple >= 0) {
        ctx.beginPath(); ctx.arc(x, y, r + 6 + 46 * f.ripple, 0, 2 * Math.PI);
        ctx.lineWidth = 2;
        ctx.strokeStyle = 'rgba(255,255,255,' + (0.8 * (1 - f.ripple)) + ')';
        ctx.stroke();
      }
      f.dotX = x; f.dotY = y; f.dotR = r;
    }
  }

  // The pill follows the focused dot on its own spring and stays inside the safe area.
  var pillX = new Spring(0.35, 0.86, 960), pillY = new Spring(0.35, 0.86, 540), pillShown = false;

  function placePill(snap) {
    var f = fixtures[focus];
    if (!f || f.dotX === undefined) return;
    var w = pill.offsetWidth || 300, h = pill.offsetHeight || 52;
    var x = Math.max(SAFE.left + w / 2, Math.min(SAFE.right - w / 2, f.dotX));
    var y = Math.max(SAFE.top, Math.min(SAFE.bottom - h, f.dotY - f.dotR - 16 - h));
    pillX.to = x; pillY.to = y;
    if (snap) { pillX.x = x; pillY.x = y; pillX.v = pillY.v = 0; }
    pill.style.webkitTransform = pill.style.transform = 'translate(' + Math.round(pillX.x - w / 2) + 'px,' + Math.round(pillY.x) + 'px)';
  }

  function showCaption() {
    var f = fixtures[focus];
    if (!focused || !f) { pill.className = 'pill'; pillShown = false; return; }
    pillName.textContent = roomName(f.def.room) + '  ·  ' + name(f.def.name);
    pillState.textContent = '  ·  ' + (f.on ? tr('On') : tr('Off'));
    pill.className = 'pill on' + (f.on ? ' lit' : '');
    if (!pillShown) placePill(true);
    pillShown = true;
  }

  // MARK: - Animation

  function cameraTarget() {
    var f = fixtures[focus], room = focused && f ? rooms[f.def.room] : null;
    target(room && room.camera ? room.camera : OVER);
    for (var i = 0; i < roomList.length; i++) roomList[i].emph.to = !focused || !room || roomList[i] === room ? 1 : 0;
  }

  function tick(now) {
    frame = 0;
    var dt = last ? Math.min(0.05, (now - last) / 1000) : 1 / 60, moving = false, i, k;
    last = now;
    for (k in cam) if (cam.hasOwnProperty(k) && cam[k].step(dt)) moving = true;
    for (i = 0; i < fixtures.length; i++) {
      var f = fixtures[i];
      f.grow.to = focused && i === focus ? 1 : 0;
      f.lit.to = f.on ? 1 : 0;
      if (f.grow.step(dt)) moving = true;
      if (f.lit.step(dt)) moving = true;
      if (f.ignite > 0.005) { f.ignite *= Math.exp(-3.5 * dt); moving = true; } else f.ignite = 0;
      if (f.ripple >= 0) { f.ripple += dt / 0.55; moving = true; if (f.ripple >= 1) f.ripple = -1; }
    }
    for (i = 0; i < roomList.length; i++) {
      var room = roomList[i], on = 0;
      for (k = 0; k < fixtures.length; k++) if (fixtures[k].def.room === room.def.id && fixtures[k].on) on = 1;
      room.glow.to = on;
      if (room.glow.step(dt)) moving = true;
      if (room.emph.step(dt)) moving = true;
    }
    // A slow breathing drift of the camera once the remote has been still for a while (never with Reduce Motion).
    var idle = !reduced && !lite && Date.now() - lastKey > 8000;
    drift = idle ? 1.5 * Math.sin(now / 1000 * 2 * Math.PI / 40) : drift * 0.9;
    if (idle || Math.abs(drift) > 0.01) moving = true;
    aim(cam.yaw.x + drift, cam.pitch.x, cam.zoom.x, cam.cx.x, cam.cy.x);
    // Outside walls stand tall when the camera sees their inside, and lower themselves when they face it.
    for (i = 0; i < wallList.length; i++) {
      var wl = wallList[i], o = wl.def.out;
      if (!o) continue;
      var v = -o[0] * P.sin - o[1] * P.cos, t = Math.max(0, Math.min(1, (v + 0.12) / 0.24));
      wl.tall = t * t * (3 - 2 * t);
      wl.h = wl.def.low + (wl.def.h - wl.def.low) * wl.tall;
    }
    draw();
    if (pillShown) {
      if (pillX.step(dt)) moving = true;
      if (pillY.step(dt)) moving = true;
      placePill(false);
    }
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

  window.House = {
    init: function (layer) {
      canvas = layer.querySelector('canvas');
      pill = layer.querySelector('.pill');
      pillName = pill.querySelector('.n');
      pillState = pill.querySelector('.s');
      ctx = canvas.getContext('2d');
      lite = (' ' + document.body.className + ' ').indexOf(' lite ') >= 0;
      try { reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) {}
      setup();
      // The light nearest the middle of the overview gets the focus first.
      var bestD = 1e9;
      for (var i = 0; i < fixtures.length; i++) {
        var d = Math.abs(fixtures[i].nx - 960) + Math.abs(fixtures[i].ny - 560);
        if (d < bestD) { bestD = d; focus = i; }
      }
      aim(OVER.yaw, OVER.pitch, 1, OVER.cx, OVER.cy);
      draw();
    },
    show: function (on) { shown = on; if (on) kick(); },
    focus: function (on) { focused = on; lastKey = Date.now(); cameraTarget(); showCaption(); kick(); },
    /** false when there's no light that way and the press was ↑: the tab bar takes the focus. */
    move: function (dx, dy) {
      lastKey = Date.now();
      var n = next(dx, dy);
      if (n < 0) return !(dy < 0);
      focus = n;
      cameraTarget();
      showCaption();
      kick();
      return true;
    },
    toggle: function () {
      var f = fixtures[focus];
      if (!f) return;
      lastKey = Date.now();
      f.on = !f.on;
      if (f.on) f.ignite = 1;
      f.ripple = 0;
      showCaption();
      kick();
    },
    /** For Welly's cards and the preview: a light by id, on or off. */
    set: function (id, on) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id && fixtures[i].on !== on) { fixtures[i].on = on; if (on) fixtures[i].ignite = 1; }
      showCaption();
      kick();
    },
    /** Moves the focus to a light by id (the screenshots, Welly's home cards). */
    select: function (id) {
      for (var i = 0; i < fixtures.length; i++) if (fixtures[i].def.id === id) focus = i;
      cameraTarget();
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
