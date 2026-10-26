"""Writes app/assets/house.json (the Home tab's house) from the apartment's blueprint.

Everything below is in the blueprint's own coordinates, in metres: x to the east (right on the plan), y to the south
(down on the plan), origin at the plan's top-left. The blueprint image is about 30 px per metre (the bedrooms are
~3 m wide). The overview keeps the blueprint's orientation (balcony at the back, bedrooms in front, the parents' room in
the front-right corner); each room with a light has its own camera, and the renderers swing to it.

A clay diorama: matte pastel materials, colour mostly from rugs and bedding, warm oak floors. Furniture is built from
several boxes (legs, cushions, pillows, a folded duvet) so each piece reads at a glance; closets are wall-coloured
masses with white doors, not white blocks.

Schema (read by HouseView.java and web/rooms.js):
  camera   overview {yaw, pitch, box}; rooms[].camera {yaw, pitch, zoom, center} frames that room
  walls    {from, to, h, out?, id?}: with `out` (outward normal) an outside wall is h tall when the camera sees its
           inside and LOW when it faces the camera; inner walls keep h
  furniture{room, kind, box [x, y, z, w, d, h], color, shape? "round" | "disc" (with face +x/-x/+y/-y: a flat
           circle on that side, shown when it faces the camera), alpha?, wall? (scales with that wall's height:
           doors, art, the sliding door), shadow? false}
  walls get a baseboard on each side that faces the camera
  fixtures {id, room, kind bulb|lamp|tube, name, at, glow, height (lamp), along/length (tube)}
  palette  cool (lights off) and warm (lights on) for every colour, and the glow

Usage: python3 tools/make_house.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
W, D = 9.3, 14.8
LOW, CUT, TALL = 0.45, 1.1, 2.6     # an outside wall facing the camera, inner walls, an outside wall seen from inside

ROOMS = [
    # id, name, rect (x0, y0, x1, y1), floor, camera (None: the overview)
    ('dining', 'Dining Room', (1.6, 0, 4.75, 3.35), 'wood', None),
    ('balcony', 'Balcony', (4.75, 0, 9.3, 2.0), 'deck', None),
    ('kitchen', 'Kitchen', (1.6, 3.35, 4.2, 5.85), 'tile', None),
    # Seen from the dining side, so the TV faces the camera and the sectional's seats too.
    ('living', 'Living Room', (4.2, 2.0, 9.3, 7.1), 'wood',
     {'yaw': -140, 'pitch': 50, 'zoom': 1.3, 'center': [6.75, 4.7]}),
    ('laundry', 'Laundry', (1.6, 5.85, 4.2, 7.1), 'tile', None),
    ('hall', 'Hallway', (1.6, 7.1, 5.35, 10.0), 'wood', None),
    ('powder', 'Half Bath', (5.35, 7.1, 6.85, 9.5), 'bathtile', None),
    ('bath', 'Bathroom', (6.85, 7.1, 9.3, 9.5), 'bathtile', None),
    ('corridor', 'Hallway', (5.35, 9.5, 9.3, 10.0), 'wood', None),
    ('entry', 'Entry', (0, 8.5, 2.9, 14.8), 'wood', None),
    ('mine', 'My Room', (2.9, 10.0, 5.85, 14.8), 'wood',
     {'yaw': 8, 'pitch': 54, 'zoom': 1.5, 'center': [4.4, 12.2]}),
    ('parents', "Parents' Room", (5.85, 10.0, 9.3, 14.8), 'wood',
     {'yaw': 22, 'pitch': 54, 'zoom': 1.5, 'center': [7.55, 12.2]}),
]

WALLS = [
    # The outside: each carries its outward normal, so it stands tall only when it's behind what it frames.
    dict(a=(1.6, 0), b=(4.75, 0), h=TALL, out=(0, -1), id='dining-n'),
    dict(a=(4.75, 2.0), b=(5.8, 2.0), h=TALL, out=(0, -1), id='living-n1'),
    dict(a=(7.9, 2.0), b=(9.3, 2.0), h=TALL, out=(0, -1), id='living-n2'),
    dict(a=(9.3, 2.0), b=(9.3, 14.8), h=TALL, out=(1, 0), id='east'),
    dict(a=(0, 14.8), b=(1.95, 14.8), h=TALL, out=(0, 1), id='south-1'),
    dict(a=(2.8, 14.8), b=(9.3, 14.8), h=TALL, out=(0, 1), id='south-2'),
    dict(a=(0, 8.5), b=(0, 14.8), h=TALL, out=(-1, 0), id='west-entry'),
    dict(a=(0, 8.5), b=(1.6, 8.5), h=TALL, out=(0, -1), id='entry-n'),
    dict(a=(1.6, 0), b=(1.6, 8.5), h=TALL, out=(-1, 0), id='west'),
    dict(a=(9.3, 0), b=(9.3, 2.0), h=CUT),                              # the balcony's side wall
    # Inside, cut low (a dollhouse).
    dict(a=(4.75, 0), b=(4.75, 2.0), h=CUT),
    dict(a=(4.2, 3.35), b=(4.2, 7.1), h=CUT),
    dict(a=(1.6, 5.85), b=(4.2, 5.85), h=CUT),
    dict(a=(1.6, 7.1), b=(4.2, 7.1), h=CUT),                            # the laundry closet's bifold doors
    dict(a=(5.35, 7.1), b=(9.3, 7.1), h=CUT),                           # the TV wall
    dict(a=(5.35, 7.1), b=(5.35, 9.5), h=CUT),
    dict(a=(6.85, 7.1), b=(6.85, 9.5), h=CUT),
    dict(a=(5.35, 9.5), b=(5.95, 9.5), h=CUT), dict(a=(6.65, 9.5), b=(7.05, 9.5), h=CUT),
    dict(a=(7.75, 9.5), b=(9.3, 9.5), h=CUT),
    dict(a=(2.9, 10.0), b=(2.9, 14.8), h=CUT),                          # my room's tube light wall
    dict(a=(2.9, 10.0), b=(5.0, 10.0), h=CUT),                          # my room's door at 5.0-5.8
    dict(a=(5.8, 10.0), b=(5.95, 10.0), h=CUT),
    dict(a=(5.85, 10.0), b=(5.85, 14.8), h=CUT),                        # between the bedrooms
    dict(a=(6.95, 10.0), b=(9.3, 10.0), h=CUT),                         # the parents' door at 5.95-6.95
]

F = []


def box(room, kind, x, y, z, w, d, h, color, **extra):
    F.append(dict(room=room, kind=kind, box=[round(v, 3) for v in (x, y, z, w, d, h)], color=color, **extra))


def legs(room, x, y, w, d, h, color, inset=0.05, size=0.05):
    for lx in (x + inset, x + w - inset - size):
        for ly in (y + inset, y + d - inset - size):
            box(room, 'leg', lx, ly, 0, size, size, h, color, shadow=False)


def bed(room, x, y, w, l, frame, duvet, fold, rug=None):
    """A bed with its headboard at the south end (y + l): legs, frame, mattress, a duvet over the foot two thirds with
    a folded band, two pillows, and a rug under its foot."""
    if rug:
        box(room, 'rug', x - 0.45, y - 0.35, 0, w + 0.9, l * 0.62, 0.015, rug)
    legs(room, x, y, w, l - 0.08, 0.1, 'oak')
    box(room, 'bed', x, y, 0.1, w, l - 0.08, 0.24, frame)
    box(room, 'headboard', x - 0.03, y + l - 0.1, 0, w + 0.06, 0.1, 0.98, frame)
    box(room, 'mattress', x + 0.04, y + 0.04, 0.34, w - 0.08, l - 0.18, 0.16, 'mattress')
    box(room, 'duvet', x, y + 0.02, 0.5, w, l * 0.6, 0.06, duvet)
    box(room, 'duvet-fold', x, y + l * 0.6 - 0.16, 0.56, w, 0.2, 0.05, fold)
    pw = (w - 0.24) / 2
    for px in (x + 0.08, x + 0.16 + pw):
        box(room, 'pillow', px, y + l - 0.5, 0.5, pw, 0.34, 0.13, 'pillow')


def disc(room, kind, cx, cy, cz, r, face, color):
    """A flat round detail on a face: a washer's door, a dial. `face` is +x, -x, +y or -y (it shows only when that
    side faces the camera); up-facing ones (burners) are short cylinders."""
    if face == 'up':
        box(room, kind, cx - r, cy - r, cz, 2 * r, 2 * r, 0.008, color, shape='round', shadow=False)
    elif face in ('+y', '-y'):
        box(room, kind, cx - r, cy, cz - r, 2 * r, 0.004, 2 * r, color, shape='disc', face=face, shadow=False)
    else:
        box(room, kind, cx, cy - r, cz - r, 0.004, 2 * r, 2 * r, color, shape='disc', face=face, shadow=False)


def desk(room, x, y, w, d, top='oak', legs_color='charcoal'):
    box(room, 'desk-leg', x + 0.02, y + 0.04, 0, 0.05, d - 0.08, 0.72, legs_color, shadow=False)
    box(room, 'desk-leg', x + w - 0.07, y + 0.04, 0, 0.05, d - 0.08, 0.72, legs_color, shadow=False)
    box(room, 'desk', x, y, 0.72, w, d, 0.04, top)


def monitor(room, x, y, w, facing):
    """A monitor on a stand at (x, y) on a desk (top at 0.76), screen toward `facing` (+x or -x): a foot, a neck, a
    bezel, the screen just in front of it, then a keyboard and a mouse in front of the screen."""
    s = 1 if facing == '+x' else -1
    box(room, 'stand-foot', x - 0.09, y + w / 2 - 0.08, 0.76, 0.18, 0.16, 0.012, 'charcoal', shadow=False)
    box(room, 'stand-neck', x - 0.02, y + w / 2 - 0.02, 0.772, 0.04, 0.04, 0.12, 'charcoal', shadow=False)
    box(room, 'bezel', x - 0.02, y, 0.88, 0.04, w, 0.34, 'bezel', shadow=False)
    box(room, 'screen', x + (0.02 if s > 0 else -0.026), y + 0.02, 0.9, 0.006, w - 0.04, 0.3, 'screen', shadow=False)
    box(room, 'keyboard', x + s * 0.2 - 0.07, y + 0.08, 0.76, 0.14, w - 0.16, 0.018, 'silver', shadow=False)
    box(room, 'mouse', x + s * 0.2 - 0.03, y + w + 0.02, 0.76, 0.06, 0.09, 0.025, 'charcoal', shadow=False)


def chair(room, x, y, back='north', color='chair', h=0.46, back_h=0.5, gaming=False):
    """An office chair: a round base, a post, the seat, and its back on the `back` side (north/south/east/west)."""
    s = 0.5 if not gaming else 0.56
    box(room, 'chair-base', x + s / 2 - 0.2, y + s / 2 - 0.2, 0, 0.4, 0.4, 0.06, 'charcoal', shape='round')
    box(room, 'chair-post', x + s / 2 - 0.03, y + s / 2 - 0.03, 0.06, 0.06, 0.06, h - 0.14, 'charcoal', shadow=False)
    box(room, 'seat', x, y, h - 0.08, s, s, 0.08, color, shadow=False)
    tall = back_h + (0.2 if gaming else 0)
    if back in ('north', 'south'):
        by = y - 0.02 if back == 'north' else y + s - 0.06
        box(room, 'chair-back', x, by, h, s, 0.08, tall, color, shadow=False)
    else:
        bx = x - 0.02 if back == 'west' else x + s - 0.06
        box(room, 'chair-back', bx, y, h, 0.08, s, tall, color, shadow=False)


def washer(room, x, y, w=0.7, d=0.68, h=0.94, kind='washer'):
    """A front-load washer (or dryer) facing south: the body, a control strip with a dial and a detergent drawer, and
    a round door: a metal ring around dark glass."""
    box(room, kind, x, y, 0, w, d, h, 'white')
    front = y + d
    box(room, 'controls', x + 0.04, front, h - 0.15, w - 0.08, 0.006, 0.12, 'silver', shadow=False)
    box(room, 'drawer', x + 0.07, front + 0.006, h - 0.13, 0.18, 0.01, 0.08, 'white', shadow=False)
    disc(room, 'dial', x + w - 0.14, front + 0.008, h - 0.09, 0.035, '+y', 'charcoal')
    disc(room, 'door-ring', x + w / 2, front + 0.004, 0.42, 0.21, '+y', 'silver')
    disc(room, 'door-glass', x + w / 2, front + 0.009, 0.42, 0.15, '+y', 'glass-dark')


def heater(room, x, y, size=0.5, h=1.3):
    """A water heater: a slate tank with a lighter cap, two copper pipes out of the top, and a control box with a
    dial and a green status light on its front (south)."""
    box(room, 'tank', x, y, 0, size, size, h, 'heater', shape='round')
    box(room, 'cap', x + 0.05, y + 0.05, h, size - 0.1, size - 0.1, 0.06, 'silver', shape='round', shadow=False)
    for px in (x + size * 0.3, x + size * 0.62):
        box(room, 'pipe', px, y + size / 2 - 0.025, h + 0.06, 0.05, 0.05, 0.3, 'copper', shape='round', shadow=False)
    box(room, 'control', x + size / 2 - 0.09, y + size - 0.02, 0.22, 0.18, 0.05, 0.13, 'charcoal', shadow=False)
    disc(room, 'dial', x + size / 2 - 0.03, y + size + 0.032, 0.285, 0.035, '+y', 'silver')
    disc(room, 'status', x + size / 2 + 0.05, y + size + 0.032, 0.31, 0.012, '+y', 'indicator')


def plant(room, x, y, size=0.36, tall=0.9, pot='pot'):
    box(room, 'pot', x, y, 0, size, size, 0.32, pot, shape='round')
    box(room, 'plant', x - 0.08, y - 0.08, 0.32, size + 0.16, size + 0.16, tall - 0.32, 'plant', shape='round',
        shadow=False)


def closet(room, x, y, w, d, face, wall_h=CUT):
    """A built-in closet: a wall-coloured mass the height of the cut walls, two white doors on its open face."""
    box(room, 'closet', x, y, 0, w, d, wall_h, 'wall', shadow=False)
    gap = 0.02
    if face in ('south', 'north'):
        fy = y + d if face == 'south' else y - 0.03
        half = (w - 0.12 - gap) / 2
        for i in range(2):
            box(room, 'closet-door', x + 0.06 + i * (half + gap), fy, 0.02, half, 0.03, wall_h - 0.06, 'door',
                shadow=False)
    else:
        fx = x + w if face == 'east' else x - 0.03
        half = (d - 0.12 - gap) / 2
        for i in range(2):
            box(room, 'closet-door', fx, y + 0.06 + i * (half + gap), 0.02, 0.03, half, wall_h - 0.06, 'door',
                shadow=False)


def art(room, wall, x, y, z, w, d, h, color, face):
    """A framed print on a wall: the frame, then the print just in front of it, toward `face`."""
    extra = {'wall': wall} if wall else {}
    box(room, 'frame', x, y, z, w, d, h, 'frame', shadow=False, **extra)
    if face in ('south', 'north'):
        py = y + d if face == 'south' else y - 0.01
        box(room, 'print', x + 0.04, py, z + 0.04, w - 0.08, 0.01, h - 0.08, color, shadow=False, **extra)
    else:
        px = x + w if face == 'east' else x - 0.01
        box(room, 'print', px, y + 0.04, z + 0.04, 0.01, d - 0.08, h - 0.08, color, shadow=False, **extra)


# --- Living room: the sectional along the east wall, its return under the balcony door, a chaise toward the TV with
# a metre of floor in front of the console, the rug, an oak and glass coffee table, the floating console under the TV
# on the bathroom wall, the keyboard by the kitchen wall, a floor plant, the corner floor lamp.
box('living', 'rug', 5.35, 3.15, 0, 2.85, 2.65, 0.02, 'rug')
box('living', 'rug', 5.6, 3.4, 0.02, 2.35, 2.15, 0.004, 'rug-pattern')
box('living', 'rug', 6.05, 3.85, 0.024, 1.45, 1.25, 0.003, 'rug')
# The long run along the east wall, then the return along the north wall.
box('living', 'sofa', 8.3, 2.95, 0.06, 0.95, 2.65, 0.26, 'sofa')
box('living', 'sofa-back', 9.0, 2.2, 0.32, 0.25, 3.4, 0.5, 'sofa')
box('living', 'sofa', 6.2, 2.2, 0.06, 3.05, 0.78, 0.26, 'sofa')
box('living', 'sofa-back', 6.2, 2.15, 0.32, 2.8, 0.25, 0.5, 'sofa')
box('living', 'sofa-arm', 6.2, 2.4, 0.06, 0.22, 0.78, 0.56, 'sofa')
box('living', 'chaise', 7.35, 4.65, 0.06, 0.95, 0.95, 0.26, 'sofa')
box('living', 'sofa', 8.3, 4.65, 0.06, 0.95, 0.95, 0.26, 'sofa')
for x0, y0, w, d in ((8.33, 2.45, 0.66, 0.85), (8.33, 3.33, 0.66, 0.66), (8.33, 4.02, 0.66, 0.6),
                     (8.33, 4.68, 0.66, 0.9), (7.38, 4.68, 0.9, 0.9),
                     (6.45, 2.45, 0.9, 0.52), (7.38, 2.45, 0.9, 0.52)):
    box('living', 'cushion', x0, y0, 0.32, w, d, 0.12, 'cushion', shadow=False)
box('living', 'pillow', 8.78, 2.95, 0.44, 0.18, 0.46, 0.4, 'pillow-terracotta', shadow=False)
box('living', 'pillow', 8.78, 3.55, 0.44, 0.18, 0.46, 0.4, 'pillow-slate', shadow=False)
box('living', 'pillow', 6.75, 2.42, 0.44, 0.46, 0.18, 0.4, 'pillow-slate', shadow=False)
box('living', 'pillow', 7.6, 2.42, 0.44, 0.46, 0.18, 0.4, 'pillow', shadow=False)
# The oak and glass table beside the keyboard by the kitchen wall (the rug's centre stays open).
legs('living', 4.8, 5.15, 0.62, 1.15, 0.38, 'oak', inset=0.02, size=0.05)
box('living', 'shelf', 4.82, 5.17, 0.12, 0.58, 1.11, 0.03, 'oak', shadow=False)
box('living', 'glass', 4.77, 5.12, 0.38, 0.68, 1.21, 0.03, 'glass', alpha=0.62)
box('living', 'book', 4.9, 5.4, 0.41, 0.24, 0.3, 0.04, 'pillow-terracotta', shadow=False)
# The floating console and the black glass TV above it (thin bezel, screen just in front).
box('living', 'console', 6.1, 6.68, 0.22, 1.8, 0.34, 0.3, 'oak')
box('living', 'tv', 6.15, 6.99, 0.82, 1.7, 0.04, 0.98, 'bezel', focusable=False, shadow=False)
box('living', 'screen', 6.18, 6.975, 0.85, 1.64, 0.015, 0.92, 'screen', focusable=False, shadow=False)
box('living', 'remote', 7.3, 6.78, 0.52, 0.16, 0.05, 0.02, 'charcoal', shadow=False)
plant('living', 8.7, 6.45, size=0.38, tall=1.15)
box('living', 'piano', 4.3, 5.15, 0.62, 0.36, 1.2, 0.12, 'silver')
legs('living', 4.3, 5.15, 0.36, 1.2, 0.62, 'charcoal', inset=0.02, size=0.04)
box('living', 'cabinet', 4.85, 2.12, 0, 0.85, 0.48, 0.78, 'white')
# The balcony's sliding glass door in the north wall (it lowers with the wall when the camera turns around).
box('living', 'door-glass', 5.8, 1.98, 0, 2.1, 0.04, 2.1, 'glass', alpha=0.5, wall='living-n1', shadow=False)
box('living', 'door-frame', 6.83, 1.97, 0, 0.04, 0.06, 2.1, 'frame', wall='living-n1', shadow=False)
art('living', 'living-n2', 8.05, 2.06, 1.3, 0.75, 0.03, 0.55, 'print-sky', 'south')

# --- Balcony: concrete, a railing of thin posts, a plant.
box('balcony', 'rail', 4.85, 0.04, 0.88, 4.35, 0.05, 0.05, 'charcoal', shadow=False)
for i in range(12):
    box('balcony', 'post', 4.9 + i * 0.38, 0.04, 0, 0.04, 0.04, 0.88, 'charcoal', shadow=False)
plant('balcony', 8.6, 0.35, size=0.34, tall=0.75, pot='pot-white')

# --- Dining room: an oak table on legs and four chairs, by the window; a print on the west wall.
box('dining', 'window', 2.55, 0.06, 0.95, 1.1, 0.02, 1.15, 'window', wall='dining-n', shadow=False)
legs('dining', 2.45, 1.0, 1.4, 0.85, 0.72, 'oak', inset=0.04, size=0.06)
box('dining', 'table', 2.45, 1.0, 0.72, 1.4, 0.85, 0.04, 'oak')
for x, y, back in ((2.55, 0.42, 'north'), (3.3, 0.42, 'north'), (2.55, 1.98, 'south'), (3.3, 1.98, 'south')):
    legs('dining', x, y, 0.42, 0.42, 0.42, 'oak', inset=0.02, size=0.04)
    box('dining', 'seat', x, y, 0.42, 0.42, 0.42, 0.05, 'chair', shadow=False)
    by = y if back == 'north' else y + 0.36
    box('dining', 'chair-back', x, by, 0.47, 0.42, 0.06, 0.4, 'chair', shadow=False)
art('dining', 'west', 1.66, 1.2, 1.25, 0.03, 0.85, 0.6, 'print-sage', 'east')
plant('dining', 4.25, 0.3, size=0.36, tall=1.0)

# --- Kitchen: a galley, oak cabinets with granite tops, white appliances, upper cabinets on the west wall.
box('kitchen', 'cabinet', 1.65, 3.45, 0, 0.62, 0.7, 0.86, 'oak')
box('kitchen', 'counter', 1.65, 3.45, 0.86, 0.62, 0.7, 0.04, 'granite', shadow=False)
# The stove (front to the east): a black cooktop with four burners, the oven's window and handle.
box('kitchen', 'stove', 1.65, 4.15, 0, 0.64, 0.76, 0.9, 'white')
box('kitchen', 'cooktop', 1.7, 4.2, 0.9, 0.54, 0.66, 0.015, 'charcoal', shadow=False)
for bx, by, br in ((1.84, 4.34, 0.08), (2.1, 4.34, 0.065), (1.84, 4.72, 0.065), (2.1, 4.72, 0.08)):
    disc('kitchen', 'burner', bx, by, 0.915, br, 'up', 'burner')
box('kitchen', 'oven-window', 2.29, 4.28, 0.22, 0.006, 0.5, 0.36, 'glass-dark', shadow=False)
box('kitchen', 'oven-handle', 2.296, 4.25, 0.66, 0.025, 0.56, 0.03, 'silver', shadow=False)
box('kitchen', 'cabinet', 1.65, 4.91, 0, 0.62, 0.2, 0.86, 'oak')
# The fridge (front to the east): two doors and their handles.
box('kitchen', 'fridge', 1.65, 5.12, 0, 0.72, 0.7, 1.75, 'white')
box('kitchen', 'fridge-gap', 2.37, 5.12, 1.12, 0.006, 0.7, 0.015, 'silver', shadow=False)
box('kitchen', 'handle', 2.376, 5.2, 1.22, 0.025, 0.03, 0.42, 'silver', shadow=False)
box('kitchen', 'handle', 2.376, 5.2, 0.62, 0.025, 0.03, 0.4, 'silver', shadow=False)
box('kitchen', 'upper', 1.66, 3.45, 1.45, 0.34, 1.45, 0.7, 'oak', wall='west', shadow=False)
box('kitchen', 'cabinet', 3.52, 3.75, 0, 0.62, 2.0, 0.86, 'oak')
box('kitchen', 'counter', 3.52, 3.75, 0.86, 0.62, 2.0, 0.04, 'granite', shadow=False)
box('kitchen', 'sink', 3.6, 4.35, 0.9, 0.46, 0.6, 0.01, 'basin', shadow=False)
box('kitchen', 'faucet', 4.02, 4.62, 0.9, 0.04, 0.04, 0.26, 'silver', shadow=False)
box('kitchen', 'spout', 3.86, 4.625, 1.14, 0.18, 0.03, 0.025, 'silver', shadow=False)
for cx in (3.7, 3.9, 4.1):
    box('kitchen', 'canister', cx - 0.05, 3.85, 0.9, 0.1, 0.1, 0.16, 'pot-white', shape='round', shadow=False)

# --- Laundry closet: washer, dryer, the water heater; its bifold doors face the hallway.
washer('laundry', 1.75, 5.95)
washer('laundry', 2.5, 5.95, kind='dryer')
heater('laundry', 3.38, 6.0)
for i in range(4):
    box('laundry', 'closet-door', 1.7 + i * 0.62, 7.16, 0.02, 0.6, 0.03, CUT - 0.06, 'door', shadow=False)

# --- Hallway: the drying rack, the laundry basket, the linen closet, a stool.
box('hall', 'rack', 2.0, 7.3, 0.85, 0.55, 0.28, 0.03, 'silver', shadow=False)
legs('hall', 2.0, 7.3, 0.55, 0.28, 0.85, 'silver', inset=0.0, size=0.03)
box('hall', 'basket', 2.75, 7.35, 0, 0.38, 0.38, 0.6, 'white', shape='round')
box('hall', 'stool', 4.7, 8.6, 0, 0.32, 0.32, 0.45, 'charcoal', shape='round')

# --- Bathrooms: the half bath's toilet and sink, the full bath's vanity, toilet and tub, bath mats.
box('powder', 'toilet-tank', 5.55, 7.18, 0, 0.42, 0.2, 0.75, 'white')
box('powder', 'toilet', 5.58, 7.38, 0, 0.36, 0.44, 0.42, 'white', shape='round')
box('powder', 'vanity', 6.15, 7.18, 0, 0.6, 0.46, 0.84, 'white')
box('powder', 'basin', 6.27, 7.25, 0.84, 0.36, 0.32, 0.01, 'basin', shape='round', shadow=False)
box('powder', 'faucet', 6.43, 7.2, 0.84, 0.04, 0.05, 0.14, 'silver', shadow=False)
box('bath', 'vanity', 7.0, 7.18, 0, 0.75, 0.48, 0.84, 'white')
box('bath', 'basin', 7.16, 7.25, 0.84, 0.4, 0.34, 0.01, 'basin', shape='round', shadow=False)
box('bath', 'faucet', 7.34, 7.2, 0.84, 0.04, 0.05, 0.14, 'silver', shadow=False)
box('bath', 'tap', 9.1, 8.98, 0.55, 0.06, 0.04, 0.1, 'silver', shadow=False)
box('bath', 'toilet-tank', 7.95, 7.18, 0, 0.42, 0.2, 0.75, 'white')
box('bath', 'toilet', 7.98, 7.38, 0, 0.36, 0.44, 0.42, 'white', shape='round')
box('bath', 'tub', 7.5, 8.65, 0, 1.75, 0.8, 0.55, 'white')
box('bath', 'tub-water', 7.6, 8.75, 0.55, 1.55, 0.6, 0.005, 'basin', shadow=False)
box('bath', 'mat', 7.75, 8.15, 0, 0.7, 0.42, 0.01, 'rug-sage')

# --- Entry (the foyer): the front door standing open, a coat closet and hooks on the west wall, a shoe bench, a
# plant by the door.
# The front door in the south wall (the side the beds' headboards are on): oak threshold, frame and header, the door
# standing open into the foyer, a mat inside.
box('entry', 'threshold', 1.95, 14.7, 0, 0.85, 0.1, 0.012, 'oak', shadow=False)
box('entry', 'door-frame', 1.86, 14.72, 0, 0.09, 0.1, 2.12, 'frame', shadow=False)
box('entry', 'door-frame', 2.8, 14.72, 0, 0.09, 0.1, 2.12, 'frame', shadow=False)
box('entry', 'door-header', 1.86, 14.72, 2.04, 1.03, 0.1, 0.08, 'frame', shadow=False)
box('entry', 'door', 2.74, 13.9, 0, 0.05, 0.8, 2.02, 'door')
box('entry', 'knob', 2.71, 14.0, 0.95, 0.03, 0.04, 0.04, 'silver', shadow=False)
box('entry', 'mat', 2.0, 14.0, 0, 0.72, 0.5, 0.012, 'rug-sand')
closet('entry', 0.08, 9.55, 0.62, 1.7, 'east', wall_h=CUT)
box('entry', 'hooks', 0.06, 11.55, 1.55, 0.04, 0.95, 0.04, 'charcoal', wall='west-entry', shadow=False)
box('entry', 'coat', 0.08, 11.65, 0.8, 0.14, 0.3, 0.75, 'coat-1', wall='west-entry', shadow=False)
box('entry', 'coat', 0.08, 12.05, 0.85, 0.14, 0.3, 0.7, 'coat-2', wall='west-entry', shadow=False)
legs('entry', 0.1, 12.75, 0.42, 1.2, 0.36, 'oak', inset=0.02, size=0.05)
box('entry', 'bench', 0.1, 12.75, 0.36, 0.42, 1.2, 0.06, 'oak')
box('entry', 'cushion', 0.12, 12.8, 0.42, 0.38, 1.1, 0.06, 'pillow-slate', shadow=False)
box('entry', 'shoes', 0.16, 12.95, 0.06, 0.28, 0.16, 0.08, 'charcoal', shadow=False)
box('entry', 'shoes', 0.16, 13.35, 0.06, 0.28, 0.16, 0.08, 'pillow-terracotta', shadow=False)
plant('entry', 0.25, 14.2, size=0.38, tall=1.2)

# --- My room: the closet by the door, the black cube shelf under the tube light, the bed along that wall with its
# headboard at the window wall, the desk and gaming chair under the window, the white bookshelf across from the tube
# light with the lamp just behind it, posters over the bed.
closet('mine', 2.98, 10.04, 1.87, 0.56, 'south')
box('mine', 'cube-shelf', 2.98, 10.85, 0, 0.4, 1.1, 0.82, 'charcoal')
for i, (yy, zz, c) in enumerate(((10.9, 0.06, 'bin-1'), (11.42, 0.06, 'bin-2'), (10.9, 0.44, 'bin-2'),
                                  (11.42, 0.44, 'bin-1'))):
    box('mine', 'bin', 3.38, yy, zz, 0.01, 0.48, 0.32, c, shadow=False)
bed('mine', 2.98, 12.55, 1.45, 2.15, 'upholstery', 'bedding', 'green', rug='rug-sand')
art('mine', None, 2.96, 12.8, 0.62, 0.03, 0.42, 0.34, 'print-sky', 'east')
art('mine', None, 2.96, 13.35, 0.62, 0.03, 0.42, 0.34, 'print-terracotta', 'east')
# The desk against the wall shared with the parents' room, facing it (east); the gaming chair on the room's side.
desk('mine', 5.17, 13.3, 0.62, 1.35)
monitor('mine', 5.68, 13.68, 0.55, '-x')
chair('mine', 4.55, 13.7, back='west', color='chair-dark', h=0.5, gaming=True)
box('mine', 'bookshelf', 5.44, 10.95, 0, 0.36, 0.95, 1.85, 'white')
for i, c in enumerate(('bin-1', 'pillow-terracotta', 'bin-2', 'pillow-slate')):
    box('mine', 'books', 5.42, 11.0 + (i % 2) * 0.45, 0.25 + (i // 2) * 0.6, 0.02, 0.4, 0.26, c, shadow=False)
box('mine', 'side-table', 5.38, 12.75, 0, 0.42, 0.5, 0.55, 'oak')
box('mine', 'fan-base', 3.62, 12.0, 0, 0.3, 0.3, 0.03, 'charcoal', shape='round')
box('mine', 'fan-pole', 3.75, 12.13, 0.03, 0.04, 0.04, 0.9, 'charcoal', shadow=False)
box('mine', 'fan-head', 3.6, 12.08, 0.93, 0.34, 0.14, 0.34, 'charcoal', shape='round', shadow=False)

# --- Parents' room: the closet, the black cart and the tube light on the west wall by the door, the wall TV, the bed
# (terracotta duvet) on a sage rug against the window wall, the desk and chair under the window, the silver armoire
# with its mirror, a plant.
closet('parents', 7.2, 10.04, 2.05, 0.56, 'south')
box('parents', 'cart', 5.93, 10.55, 0, 0.48, 1.0, 0.8, 'charcoal')
box('parents', 'tv', 5.92, 12.0, 0.4, 0.04, 1.1, 0.62, 'bezel', focusable=False, shadow=False)
box('parents', 'screen', 5.96, 12.03, 0.43, 0.012, 1.04, 0.56, 'screen', focusable=False, shadow=False)
bed('parents', 7.6, 12.55, 1.55, 2.15, 'navy', 'duvet', 'pillow', rug='rug-sage')
# The desk against the wall the tube light hangs on (west), facing it; the chair on the room's side.
desk('parents', 5.93, 13.3, 0.62, 1.35)
monitor('parents', 6.0, 13.68, 0.55, '+x')
chair('parents', 6.62, 13.72, back='east', color='chair')
box('parents', 'armoire', 8.78, 11.0, 0, 0.47, 1.2, 1.45, 'silver')
box('parents', 'mirror', 8.765, 11.62, 0.25, 0.015, 0.5, 1.0, 'glass', shadow=False)
box('parents', 'fan-base', 7.05, 11.3, 0, 0.3, 0.3, 0.03, 'charcoal', shape='round')
box('parents', 'fan-pole', 7.18, 11.43, 0.03, 0.04, 0.04, 0.9, 'charcoal', shadow=False)
box('parents', 'fan-head', 7.03, 11.38, 0.93, 0.34, 0.14, 0.34, 'charcoal', shape='round', shadow=False)

FIXTURES = [
    # The tube lights hang on cut walls, just under the cut, so the whole house stays in view.
    dict(id='parents.bulb', room='parents', kind='tube', name='Tube Light', at=[5.95, 11.05, 1.0], along='y',
         length=1.2, glow=[7.0, 11.6]),
    dict(id='mine.lamp', room='mine', kind='lamp', name='Lamp', at=[5.6, 12.3, 0], height=1.3, glow=[4.7, 12.3]),
    dict(id='mine.bulb', room='mine', kind='tube', name='Tube Light', at=[2.97, 11.4, 1.0], along='y', length=1.2,
         glow=[3.9, 11.6]),
    dict(id='living.bulb', room='living', kind='lamp', name='Lightbulb', at=[9.05, 2.3, 0], height=1.2,
         glow=[8.0, 3.3]),
]

# The clay palette: matte, light, the colour in the rugs and the bedding. Lights off cools and dims it; lights on
# warms it (both derived below, so they stay in step).
CLAY = {
    'floor-wood': '#C9A27C', 'floor-tile': '#DDD8D0', 'floor-bathtile': '#EBD4CF', 'floor-deck': '#C3BDB4',
    'wall': '#F1EEE9', 'wall-top': '#FFFFFF', 'wood': '#6B5A4C', 'linen': '#F4ECDF', 'window': '#BFD6E6',
    'glass': '#CFE3EE', 'frame': '#4A4D55', 'door': '#FBFAF7', 'charcoal': '#4E525B', 'chair': '#B9BCC2',
    'chair-dark': '#454950', 'white': '#F5F3EF', 'silver': '#CBCFD5', 'mattress': '#F7F5F1', 'navy': '#56658A',
    'duvet': '#DE9F80', 'upholstery': '#A6AAB2', 'bedding': '#AEC8AA', 'green': '#6FAE8C', 'pillow': '#FCFAF6',
    'pillow-terracotta': '#D7967B', 'pillow-slate': '#8E9BB0', 'sofa': '#E7DECD', 'cushion': '#EFE8DA',
    'rug': '#5E6D8E', 'rug-pattern': '#8A97B1', 'rug-sand': '#E4D6C1', 'rug-sage': '#C6D3BF', 'screen': '#15171C',
    'bezel': '#2B2E34', 'plant': '#7FAB80', 'pot': '#C98B6A', 'pot-white': '#EFECE6', 'oak': '#C99B6C',
    'granite': '#6C7078', 'basin': '#E3EEF4', 'bin-1': '#C9B79C', 'bin-2': '#9FB0C2', 'coat-1': '#7C8AA3',
    'coat-2': '#C88F6E', 'print-sky': '#9DB9D3', 'print-sage': '#B5CBA9', 'print-terracotta': '#E0A88C',
    'glass-dark': '#2E3642', 'heater': '#9AA3AE', 'copper': '#C98E62', 'burner': '#2B2D33', 'indicator': '#6FCF97',
    'baseboard': '#E2DCD3',
}
UNTINTED = {'screen', 'bezel', 'glass-dark', 'indicator'}     # the black glass stays black, lit or not
SLATE, AMBER = (0x7C, 0x88, 0x9E), (0xFF, 0xB8, 0x72)


def hex_rgb(c):
    return int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16)


def mix(a, b, t, scale=1.0):
    return '#%02X%02X%02X' % tuple(max(0, min(255, round((a[i] + (b[i] - a[i]) * t) * scale))) for i in range(3))


def palette():
    cool, warm = {}, {}
    for name, c in CLAY.items():
        rgb = hex_rgb(c)
        cool[name] = c if name in UNTINTED else mix(rgb, SLATE, 0.22, 0.9)
        warm[name] = c if name in UNTINTED else mix(rgb, AMBER, 0.16, 1.04)
    return {'cool': cool, 'warm': warm, 'glow': '#FFC07A'}


def main():
    house = {
        'about': ("The Home tab's house (WELLY-HOME-REMOTE-SPEC.md §4): the apartment's blueprint and photos, written by "
                  "tools/make_house.py (edit that, not this; its docstring describes the schema). Metres; x to the "
                  "right, y toward the viewer, z up; origin at the back-left corner, as on the blueprint."),
        'camera': {'yaw': 14, 'pitch': 60, 'box': [160, 150, 1760, 985]},
        'size': [W, D],
        'rooms': [],
        'walls': [],
        'furniture': F,
        'fixtures': FIXTURES,
        'palette': palette(),
    }
    for rid, name, (x0, y0, x1, y1), floor, camera in ROOMS:
        room = {'id': rid, 'name': name, 'rect': [x0, y0, x1, y1], 'floor': floor}
        if camera:
            room['camera'] = camera
        house['rooms'].append(room)
    for w in WALLS:
        wall = {'from': list(w['a']), 'to': list(w['b']), 'h': w['h']}
        if 'out' in w:
            wall['out'] = list(w['out'])
            wall['low'] = LOW
        if 'id' in w:
            wall['id'] = w['id']
        house['walls'].append(wall)
    path = os.path.join(ROOT, 'app', 'assets', 'house.json')
    with open(path, 'w', encoding='utf-8') as out:
        out.write(json.dumps(house, ensure_ascii=False, indent=1) + '\n')
    print('wrote', os.path.relpath(path, ROOT), '-', len(F), 'boxes')


if __name__ == '__main__':
    main()
