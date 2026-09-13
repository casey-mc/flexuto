#!/usr/bin/env python3
"""
Builds the "related emoji" table used by the emoji action bar.

For every emoji in java/res/raw/gemoji.json this scores every other emoji by
  - sharing a Unicode subgroup (from emoji-test.txt, e.g. "face-affection"),
  - distance in the Unicode ordering inside that subgroup,
  - sharing a group (gemoji category),
  - idf-weighted cosine similarity of keywords (gemoji description/aliases/tags
    plus the English CLDR annotations from res-large/raw/emoji_i18n.jsondlgz),
and keeps the top NEIGHBORS. Output is a gzipped JSON object:

  {"e": ["😀", "😃", ...], "n": [[1, 2, ...], ...]}

where n[i] lists indices into e, best match first.

Usage (from anywhere):  python tools/emoji-neighbors/build_neighbors.py
"""
import gzip
import json
import math
import os
import re
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
GEMOJI = os.path.join(ROOT, "java", "res", "raw", "gemoji.json")
EMOJI_TEST = os.path.join(HERE, "emoji-test.txt")
I18N = os.path.join(ROOT, "java", "res-large", "raw", "emoji_i18n.jsondlgz")
OUT = os.path.join(ROOT, "java", "res", "raw", "emoji_neighbors.gz")

NEIGHBORS = 24

W_SUBGROUP = 4.0
W_GROUP = 1.0
W_PROXIMITY = 3.0
W_KEYWORDS = 6.0

STOPWORDS = {
    "with", "and", "of", "the", "a", "in", "on", "or", "to", "for", "at", "an",
    "as", "by", "from", "is", "it", "its", "no", "not", "up", "down", "type",
}

VS16 = "️"


def norm(e):
    return e.replace(VS16, "")


def tokens(*texts):
    out = set()
    for t in texts:
        for tok in re.split(r"[^0-9a-zA-Z]+", t.lower()):
            if len(tok) > 1 and tok not in STOPWORDS:
                out.add(tok)
    return out


def load_subgroups():
    """emoji (normalized) -> (subgroup, position within emoji-test order)"""
    result = {}
    cur = None
    pos = 0
    with open(EMOJI_TEST, encoding="utf-8") as f:
        for line in f:
            if line.startswith("# subgroup:"):
                cur = line.split(":", 1)[1].strip()
                continue
            if line.startswith("#") or not line.strip():
                continue
            cps, rest = line.split(";", 1)
            if "fully-qualified" not in rest and "component" not in rest:
                continue
            e = "".join(chr(int(c, 16)) for c in cps.split())
            key = norm(e)
            if key not in result:
                result[key] = (cur, pos)
                pos += 1
    return result


def load_cldr_en():
    with gzip.open(I18N, "rt", encoding="utf-8") as f:
        while True:
            line = f.readline()
            if not line:
                return {}
            if line.startswith("#") and line[1:].strip() == "en":
                return json.loads(f.readline())


def main():
    with open(GEMOJI, encoding="utf-8") as f:
        gemoji = json.load(f)
    subgroups = load_subgroups()
    cldr = {norm(k): v for k, v in load_cldr_en().items()}

    emojis = []
    for item in gemoji:
        e = item["emoji"]
        sub, pos = subgroups.get(norm(e), (None, -1))
        toks = tokens(
            item.get("description", ""),
            " ".join(item.get("aliases", [])),
            " ".join(item.get("tags", [])),
            " ".join(cldr.get(norm(e), [])),
        )
        emojis.append({
            "emoji": e,
            "group": item.get("category", ""),
            "subgroup": sub,
            "pos": pos,
            "tokens": toks,
        })

    n = len(emojis)

    # idf weights
    df = defaultdict(int)
    for em in emojis:
        for t in em["tokens"]:
            df[t] += 1
    idf = {t: math.log(n / c) for t, c in df.items()}
    norms = [math.sqrt(sum(idf[t] ** 2 for t in em["tokens"])) or 1.0 for em in emojis]

    scores = [defaultdict(float) for _ in range(n)]

    # keyword cosine via inverted index
    index = defaultdict(list)
    for i, em in enumerate(emojis):
        for t in em["tokens"]:
            index[t].append(i)
    for t, members in index.items():
        if len(members) > 400:
            continue  # too generic to mean anything
        w2 = idf[t] ** 2
        for a in range(len(members)):
            i = members[a]
            for b in range(a + 1, len(members)):
                j = members[b]
                s = W_KEYWORDS * w2 / (norms[i] * norms[j])
                scores[i][j] += s
                scores[j][i] += s

    # group / subgroup / ordering proximity
    by_group = defaultdict(list)
    by_sub = defaultdict(list)
    for i, em in enumerate(emojis):
        by_group[em["group"]].append(i)
        if em["subgroup"]:
            by_sub[em["subgroup"]].append(i)
    for members in by_group.values():
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                i, j = members[a], members[b]
                scores[i][j] += W_GROUP
                scores[j][i] += W_GROUP
    for members in by_sub.values():
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                i, j = members[a], members[b]
                d = abs(emojis[i]["pos"] - emojis[j]["pos"])
                s = W_SUBGROUP + W_PROXIMITY / (1.0 + d / 4.0)
                scores[i][j] += s
                scores[j][i] += s

    neighbors = []
    for i in range(n):
        ranked = sorted(scores[i].items(), key=lambda kv: (-kv[1], emojis[kv[0]]["pos"]))
        neighbors.append([j for j, _ in ranked[:NEIGHBORS]])

    payload = {"e": [em["emoji"] for em in emojis], "n": neighbors}
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))

    print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes, {n} emoji)")
    for probe in ["😀", "❤️", "🍕", "🐶", "😭", "🔥", "👍", "🇺🇸", "⚽", "🎉"]:
        try:
            i = payload["e"].index(probe)
        except ValueError:
            continue
        print(probe, "".join(payload["e"][j] for j in neighbors[i][:12]))


if __name__ == "__main__":
    main()
