#!/usr/bin/env python3
"""tools/merge_i18n.py's rules for one catalogue entry."""
from merge_i18n import merge_value, rebrand, unbranded, value_for

assert merge_value(None, 'a', None) == 'a'             # only mahitTV has it
assert merge_value(None, None, 'b') == 'b'             # only upstream has it
assert merge_value('x', 'x', 'y') == 'y'               # only upstream changed it
assert merge_value('x', 'y', 'x') == 'y'               # only mahitTV changed it
assert merge_value(None, 'Wi-Fi', 'WLAN') == 'WLAN'    # both wrote it: upstream's wording
assert merge_value(None, None, None) is None
assert rebrand('Free My TV is on') == 'mahitTV is on'
assert unbranded('mahitTV is on') == 'Free My TV is on'
# A key renamed by the rebrand still finds upstream's translation.
assert value_for('mahitTV is on', {}, {}, {'Free My TV is on': 'Free My TV est activé'}) == 'mahitTV est activé'

print('test_merge_i18n: passed')
