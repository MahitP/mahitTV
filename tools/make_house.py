"""Writes app/assets/house.json (the Home tab's house) from the apartment's blueprint.

Everything below is in the blueprint's own coordinates, in metres: x to the east (right on the plan), y to the south
(down on the plan), origin at the plan's top-left. The blueprint image is about 30 px per metre (the bedrooms are
~3 m wide). The model keeps the blueprint's orientation (balcony at the back, bedrooms in front, the parents' room in
the front-right corner), seen from above at a steep angle, so it reads like the plan.

Usage: python3 tools/make_house.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
W, D = 9.3, 14.8
LOW, CUT, TALL = 0.45, 1.1, 2.6     # exterior walls toward the viewer, inner walls, the back and left walls

ROOMS = [
    # id, name, rect (x0, y0, x1, y1), floor
    ('dining', 'Dining Room', (1.6, 0, 4.75, 3.35), 'wood'),
    ('balcony', 'Balcony', (4.75, 0, 9.3, 2.0), 'deck'),
    ('kitchen', 'Kitchen', (1.6, 3.35, 4.2, 5.85), 'tile'),
    ('living', 'Living Room', (4.2, 2.0, 9.3, 7.1), 'wood'),
    ('laundry', 'Laundry', (1.6, 5.85, 4.2, 7.1), 'tile'),
    ('hall', 'Hallway', (1.6, 7.1, 5.35, 10.0), 'wood'),
    ('powder', 'Half Bath', (5.35, 7.1, 6.85, 9.5), 'bathtile'),
    ('bath', 'Bathroom', (6.85, 7.1, 9.3, 9.5), 'bathtile'),
    ('corridor', 'Hallway', (5.35, 9.5, 9.3, 10.0), 'wood'),
    ('entry', 'Entry', (0, 8.5, 2.9, 14.8), 'wood'),
    ('mine', 'My Room', (2.9, 10.0, 5.85, 14.8), 'wood'),
    ('parents', "Parents' Room", (5.85, 10.0, 9.3, 14.8), 'wood'),
]

WALLS = [
    # The outside: tall at the back (north) and left (west), low toward the viewer (south and east).
    ((1.6, 0), (4.75, 0), TALL),
    ((4.75, 2.0), (5.8, 2.0), TALL), ((7.9, 2.0), (9.3, 2.0), TALL),     # the sliding door to the balcony between
    ((9.3, 2.0), (9.3, 14.8), LOW),
    ((9.3, 0), (9.3, 2.0), CUT),                                         # the balcony's side wall
    ((0, 14.8), (1.95, 14.8), LOW), ((2.8, 14.8), (9.3, 14.8), LOW),     # the front door between
    ((0, 8.5), (0, 14.8), TALL),
    ((0, 8.5), (1.6, 8.5), TALL),
    ((1.6, 0), (1.6, 8.5), TALL),
    # Inside, cut low (a dollhouse).
    ((4.75, 0), (4.75, 2.0), CUT),
    ((4.2, 3.35), (4.2, 7.1), CUT),
    ((1.6, 5.85), (4.2, 5.85), CUT),
    ((1.6, 7.1), (4.2, 7.1), CUT),                                       # the laundry closet's bifold doors
    ((5.35, 7.1), (9.3, 7.1), CUT),                                      # the TV wall
    ((5.35, 7.1), (5.35, 9.5), CUT),
    ((6.85, 7.1), (6.85, 9.5), CUT),
    ((5.35, 9.5), (5.95, 9.5), CUT), ((6.65, 9.5), (7.05, 9.5), CUT), ((7.75, 9.5), (9.3, 9.5), CUT),
    ((2.9, 10.0), (2.9, 14.8), CUT),                                     # my room's tube light wall
    ((2.9, 10.0), (5.0, 10.0), CUT),                                     # my room's door at 5.0-5.8
    ((5.8, 10.0), (5.95, 10.0), CUT),
    ((5.85, 10.0), (5.85, 14.8), CUT),                                   # between the bedrooms
    ((6.95, 10.0), (9.3, 10.0), CUT),                                    # the parents' door at 5.95-6.95
]

F = []


def box(room, kind, x, y, z, w, d, h, color, **extra):
    F.append(dict(room=room, kind=kind, box=[x, y, z, w, d, h], color=color, **extra))


def counter(room, x, y, w, d):
    box(room, 'cabinet', x, y, 0, w, d, 0.86, 'oak')
    box(room, 'counter', x, y, 0.86, w, d, 0.04, 'granite')


# Living room (the photo from the dining side): the sectional along the east wall with its return in front of the
# balcony's curtained sliding door and a chaise toward the TV, the big patterned rug, the TV on the bathroom wall over
# a low stand with plants, the keyboard by the hallway, the fringed floor lamp in the far corner.
box('living', 'rug', 5.15, 3.05, 0, 3.15, 3.1, 0.02, 'rug')
box('living', 'rug', 5.45, 3.35, 0.02, 2.55, 2.5, 0.004, 'rug-pattern')
box('living', 'sofa', 8.3, 2.25, 0, 0.95, 4.05, 0.42, 'sofa')
box('living', 'sofa-back', 8.95, 2.25, 0.42, 0.3, 4.05, 0.4, 'sofa')
box('living', 'sofa', 6.2, 2.1, 0, 2.1, 0.95, 0.42, 'sofa')
box('living', 'sofa-back', 6.2, 2.1, 0.42, 3.05, 0.3, 0.4, 'sofa')
box('living', 'sofa-arm', 6.2, 2.4, 0.42, 0.25, 0.65, 0.2, 'sofa')
box('living', 'chaise', 7.25, 5.35, 0, 1.05, 0.95, 0.42, 'sofa')
box('living', 'pillow', 8.82, 3.0, 0.42, 0.16, 0.5, 0.42, 'pillow')
box('living', 'pillow', 8.82, 4.3, 0.42, 0.16, 0.5, 0.42, 'pillow')
box('living', 'pillow', 6.9, 2.4, 0.42, 0.5, 0.16, 0.42, 'pillow')
box('living', 'tvstand', 5.95, 6.6, 0, 2.0, 0.42, 0.45, 'charcoal')
box('living', 'tv', 6.03, 6.98, 0.55, 1.85, 0.05, 1.05, 'screen', focusable=False)
box('living', 'plant', 7.55, 6.68, 0.45, 0.18, 0.18, 0.38, 'plant')
box('living', 'plant', 6.15, 6.7, 0.45, 0.15, 0.15, 0.26, 'plant')
box('living', 'piano', 4.3, 5.9, 0, 0.4, 1.1, 0.8, 'silver')
box('living', 'cabinet', 4.85, 2.1, 0, 0.85, 0.5, 0.8, 'white')
box('living', 'door', 5.8, 1.97, 0, 2.1, 0.06, 2.1, 'window')       # the sliding glass door
box('living', 'curtain', 5.62, 2.06, 0, 0.3, 0.12, 2.2, 'curtain')
box('living', 'curtain', 7.78, 2.06, 0, 0.3, 0.12, 2.2, 'curtain')

# Balcony: concrete, a railing of thin posts.
box('balcony', 'rail', 4.85, 0.04, 0.88, 4.35, 0.05, 0.05, 'charcoal')
for i in range(12):
    box('balcony', 'post', 4.9 + i * 0.38, 0.04, 0, 0.04, 0.04, 0.88, 'charcoal')

# Dining room: a table and four chairs under the chandelier, by the window.
box('dining', 'window', 2.55, 0.06, 0.95, 1.1, 0.02, 1.15, 'window')
box('dining', 'table', 2.45, 1.0, 0, 1.4, 0.85, 0.75, 'oak')
for x, y in ((2.6, 0.55), (3.25, 0.55), (2.6, 1.9), (3.25, 1.9)):
    box('dining', 'chair', x, y, 0, 0.42, 0.42, 0.46, 'charcoal')

# Kitchen: a galley, oak cabinets, white appliances.
counter('kitchen', 1.65, 3.45, 0.62, 1.65)
box('kitchen', 'stove', 1.65, 4.15, 0, 0.64, 0.76, 0.92, 'white')
box('kitchen', 'fridge', 1.65, 5.12, 0, 0.72, 0.7, 1.75, 'white')
counter('kitchen', 3.52, 3.75, 0.62, 2.0)

# Laundry closet: washer, dryer, water heater.
box('laundry', 'washer', 1.75, 5.95, 0, 0.7, 0.68, 0.95, 'white')
box('laundry', 'dryer', 2.5, 5.95, 0, 0.7, 0.68, 0.95, 'white')
box('laundry', 'heater', 3.35, 6.0, 0, 0.5, 0.5, 1.35, 'silver')

# Hallway: the drying rack, the laundry basket, the linen closet, a stool.
box('hall', 'rack', 2.0, 7.25, 0, 0.55, 0.28, 0.95, 'silver')
box('hall', 'basket', 2.7, 7.3, 0, 0.38, 0.38, 0.62, 'white')
box('hall', 'closet', 3.05, 8.6, 0, 1.0, 0.85, CUT, 'white')
box('hall', 'stool', 4.7, 8.6, 0, 0.32, 0.32, 0.45, 'charcoal')

# Bathrooms: the half bath's toilet and sink, the full bath's vanity and tub.
box('powder', 'toilet', 5.55, 7.25, 0, 0.42, 0.68, 0.42, 'white')
box('powder', 'sink', 6.2, 7.2, 0, 0.55, 0.45, 0.85, 'white')
box('bath', 'vanity', 7.0, 7.2, 0, 0.75, 0.5, 0.85, 'white')
box('bath', 'toilet', 7.95, 7.2, 0, 0.42, 0.68, 0.42, 'white')
box('bath', 'tub', 7.55, 8.7, 0, 1.7, 0.75, 0.55, 'white')

# Entry: the stairs down to the front door, a railing, a closet on each side.
for i in range(8):
    box('entry', 'rug', 0.3, 10.6 + i * 0.28, 0, 1.5, 0.28, 0.003, 'step' if i % 2 else 'step-edge')
box('entry', 'rail', 1.8, 10.6, 0, 0.05, 2.25, 0.9, 'charcoal')
box('entry', 'closet', 0.12, 9.1, 0, 1.45, 0.6, CUT, 'white')
box('entry', 'closet', 2.25, 13.0, 0, 0.58, 1.45, CUT, 'white')

# My room: the closet by the door, the black cube shelf on the west wall under the tube light, the bed along that
# wall with its headboard at the window wall, the desk and gaming chair under the window, the white bookshelf across
# from the tube light with the lamp just behind it.
box('mine', 'closet', 2.98, 10.06, 0, 1.85, 0.6, CUT, 'white')
box('mine', 'cube-shelf', 2.98, 10.8, 0, 0.42, 1.15, 0.82, 'charcoal')
box('mine', 'fan', 3.6, 11.9, 0, 0.3, 0.3, 0.05, 'charcoal')
box('mine', 'fan-pole', 3.72, 12.02, 0.05, 0.06, 0.06, 0.85, 'charcoal')
box('mine', 'fan-head', 3.56, 11.94, 0.9, 0.38, 0.12, 0.38, 'charcoal')
box('mine', 'bed', 2.98, 12.55, 0, 1.45, 2.1, 0.38, 'upholstery')
box('mine', 'headboard', 2.98, 14.62, 0, 1.45, 0.12, 0.95, 'upholstery')
box('mine', 'duvet', 3.03, 12.65, 0.38, 1.35, 1.9, 0.16, 'bedding')
box('mine', 'pillow', 3.15, 14.2, 0.54, 1.1, 0.34, 0.12, 'pillow')
box('mine', 'blanket', 3.18, 12.85, 0.54, 1.05, 0.75, 0.04, 'green')
box('mine', 'desk', 4.62, 14.12, 0, 1.18, 0.6, 0.74, 'charcoal')
box('mine', 'monitor', 4.95, 14.55, 0.74, 0.5, 0.05, 0.32, 'screen')
box('mine', 'chair', 4.92, 13.42, 0, 0.55, 0.55, 0.5, 'charcoal')
box('mine', 'chair-back', 4.92, 13.32, 0.5, 0.55, 0.1, 0.78, 'charcoal')
box('mine', 'bookshelf', 5.42, 10.95, 0, 0.36, 0.95, 1.85, 'white')
box('mine', 'table', 5.32, 12.72, 0, 0.46, 0.6, 0.7, 'charcoal')

# Parents' room: the closet, the black cart and the tube light on the west wall by the door, the wall TV, the bed
# with its headboard at the window wall, the desk and chair under the window, the silver armoire by the door.
box('parents', 'closet', 7.2, 10.06, 0, 2.02, 0.6, CUT, 'white')
box('parents', 'cart', 5.93, 10.55, 0, 0.5, 1.0, 0.8, 'charcoal')
box('parents', 'tv', 5.92, 12.0, 0.42, 0.06, 1.1, 0.6, 'screen', focusable=False)
box('parents', 'bed', 7.6, 12.6, 0, 1.55, 2.05, 0.42, 'navy')
box('parents', 'headboard', 7.6, 14.62, 0, 1.55, 0.12, 1.0, 'navy')
box('parents', 'duvet', 7.65, 12.75, 0.42, 1.45, 1.85, 0.14, 'duvet')
box('parents', 'pillow', 7.72, 14.2, 0.56, 0.62, 0.32, 0.12, 'pillow')
box('parents', 'pillow', 8.44, 14.2, 0.56, 0.62, 0.32, 0.12, 'pillow')
box('parents', 'desk', 5.95, 14.1, 0, 1.45, 0.62, 0.74, 'charcoal')
box('parents', 'monitor', 6.4, 14.52, 0.74, 0.55, 0.05, 0.34, 'screen')
box('parents', 'chair', 6.42, 13.4, 0, 0.5, 0.5, 0.48, 'charcoal')
box('parents', 'chair-back', 6.42, 13.32, 0.48, 0.5, 0.08, 0.55, 'charcoal')
box('parents', 'fan', 6.95, 12.35, 0, 0.3, 0.3, 0.05, 'charcoal')
box('parents', 'fan-pole', 7.07, 12.47, 0.05, 0.06, 0.06, 0.9, 'charcoal')
box('parents', 'fan-head', 6.91, 12.44, 0.95, 0.38, 0.12, 0.38, 'charcoal')
box('parents', 'armoire', 8.75, 11.0, 0, 0.5, 1.2, 1.45, 'silver')

FIXTURES = [
    # The tube lights hang on cut walls, just under the cut, so the whole house stays in view.
    dict(id='parents.bulb', room='parents', kind='tube', name='Tube Light', at=[5.93, 11.05, 1.0], along='y',
         length=1.2, glow=[7.0, 11.6]),
    dict(id='mine.lamp', room='mine', kind='lamp', name='Lamp', at=[5.6, 12.3, 0], height=1.3, glow=[4.7, 12.3]),
    dict(id='mine.bulb', room='mine', kind='tube', name='Tube Light', at=[2.97, 11.35, 1.0], along='y', length=1.2,
         glow=[3.9, 11.6]),
    dict(id='living.bulb', room='living', kind='lamp', name='Lightbulb', at=[9.05, 2.3, 0], height=1.2,
         glow=[8.0, 3.3]),
]

PALETTE = {
    'cool': {'floor-wood': '#5C616D', 'floor-tile': '#9EA2A9', 'floor-bathtile': '#B8AFB4', 'floor-deck': '#868B94',
             'wall': '#D5D9DF', 'wall-top': '#EEF0F4', 'wood': '#4A4D55', 'linen': '#E2E4E8', 'window': '#AFC4D8',
             'charcoal': '#34373E', 'white': '#E6E9EE', 'silver': '#B7BCC5', 'navy': '#39445C', 'duvet': '#A9A7AE',
             'upholstery': '#8D9199', 'bedding': '#9DA1A9', 'green': '#3F7F66', 'pillow': '#E3E5EA', 'sofa': '#CDCDD0',
             'rug': '#3F475A', 'rug-pattern': '#6D7488', 'screen': '#1A1C21', 'plant': '#6E8F7E', 'oak': '#8B8178',
             'granite': '#4A4E55', 'curtain': '#8E6A6A', 'step': '#4E525C', 'step-edge': '#3E424B'},
    'warm': {'floor-wood': '#7D5B45', 'floor-tile': '#CDBFAE', 'floor-bathtile': '#E6CFC8', 'floor-deck': '#A8988A',
             'wall': '#EEDFCB', 'wall-top': '#FFF4E6', 'wood': '#5A4A3E', 'linen': '#F6E9D6', 'window': '#D6E2EA',
             'charcoal': '#3E3632', 'white': '#FBF1E3', 'silver': '#D3C8BA', 'navy': '#45405A', 'duvet': '#C7AE96',
             'upholstery': '#A59585', 'bedding': '#BCA994', 'green': '#4F8C5E', 'pillow': '#FFF0DC', 'sofa': '#EAD8BF',
             'rug': '#51465A', 'rug-pattern': '#8C7570', 'screen': '#1A1C21', 'plant': '#77985F', 'oak': '#B9895A',
             'granite': '#54504D', 'curtain': '#B5703F', 'step': '#6A5040', 'step-edge': '#57412F'},
    'glow': '#FFC07A',
}


def turn_point(x, y):
    return [round(x, 3), round(y, 3)]


def turn_box(b):
    return [round(v, 3) for v in b]


def main():
    house = {
        'about': ("The Home tab's house (WELLY-HOME-REMOTE-SPEC.md §4): the apartment's blueprint and photos, written by "
                  "tools/make_house.py (edit that, not this). Metres; x to the right, y toward the viewer, z up; origin at "
                  "the back-left corner, as on the blueprint (balcony at the back, the parents' room in the front-right "
                  "corner). Shared by the Android app "
                  "(HouseView) and the web app (rooms.js). Fixture kinds: bulb (hanging), lamp (floor lamp; height = shade "
                  "bottom), tube (wall tube light; along x or y, length); glow = where its light pools on the floor."),
        'camera': {'yaw': 14, 'pitch': 60, 'box': [160, 135, 1760, 978]},
        'size': [W, D],
        'rooms': [],
        'walls': [],
        'furniture': [],
        'fixtures': [],
        'palette': PALETTE,
    }
    for rid, name, (x0, y0, x1, y1), floor in ROOMS:
        a, b = turn_point(x1, y1), turn_point(x0, y0)
        house['rooms'].append({'id': rid, 'name': name, 'rect': [a[0], a[1], b[0], b[1]], 'floor': floor})
    for p, q, h in WALLS:
        house['walls'].append({'from': turn_point(*p), 'to': turn_point(*q), 'h': h})
    for f in F:
        f = dict(f, box=turn_box(f['box']))
        house['furniture'].append(f)
    for f in FIXTURES:
        x, y = turn_point(f['at'][0], f['at'][1])
        f = dict(f, at=[x, y, f['at'][2]], glow=turn_point(*f['glow']))
        house['fixtures'].append(f)
    path = os.path.join(ROOT, 'app', 'assets', 'house.json')
    with open(path, 'w', encoding='utf-8') as out:
        out.write(json.dumps(house, ensure_ascii=False, indent=1) + '\n')
    print('wrote', os.path.relpath(path, ROOT), '-', len(house['furniture']), 'pieces of furniture')


if __name__ == '__main__':
    main()
