#!/usr/bin/env python3
"""Merges upstream's translation catalogues into mahitTV's, key by key.

Line by line, git conflicts on every catalogue: both sides add keys in the same sorted places, though they rarely
disagree about a key. This keeps the keys the code uses, each with the value a three-way merge gives it, and says
Free My TV as mahitTV. Run it after a merge or a port (uses git's HEAD as mahitTV's side, not the working files):

    python3 tools/merge_i18n.py --base 90908dc --theirs a9e1027

It lists the keys neither side has; translate those by hand (tools/translate.py's load and save).
"""
import argparse
import json
import subprocess

import translate


def rebrand(text):
    return text.replace("Free My TV", "mahitTV")


def unbranded(key):
    return key.replace("mahitTV", "Free My TV")


def merge_value(base, ours, theirs):
    """One key, three ways. Where both sides wrote it differently, upstream's wording wins."""
    if ours is None:
        return theirs
    if theirs is None or theirs == base:
        return ours
    return theirs


def lookup(catalogue, key):
    value = catalogue.get(key)
    if value is None:
        value = catalogue.get(unbranded(key))
    return None if value is None else rebrand(value)


def value_for(key, base, ours, theirs):
    return merge_value(lookup(base, key), lookup(ours, key), lookup(theirs, key))


def catalogue_at(rev, lang):
    shown = subprocess.run(["git", "show", f"{rev}:app/assets/i18n/{lang}.json"], capture_output=True)
    return json.loads(shown.stdout.decode("utf-8")) if shown.returncode == 0 else {}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="the commit both sides started from")
    ap.add_argument("--theirs", required=True, help="the upstream commit")
    a = ap.parse_args()

    en = translate.extract()
    translate.save("en", en)
    missing = {}
    for lang in translate.languages():
        base, ours, theirs = catalogue_at(a.base, lang), catalogue_at("HEAD", lang), catalogue_at(a.theirs, lang)
        merged = {}
        for key in en:
            value = value_for(key, base, ours, theirs)
            if value is None:
                missing.setdefault(key, []).append(lang)
            else:
                merged[key] = value
        translate.save(lang, merged)
    print(f"{len(en)} keys in {len(translate.languages()) + 1} catalogues; untranslated: {len(missing)}")
    for key, langs in missing.items():
        print(f"  {key!r}: {' '.join(langs)}")


if __name__ == "__main__":
    main()
