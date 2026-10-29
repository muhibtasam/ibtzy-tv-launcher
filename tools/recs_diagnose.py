#!/usr/bin/env python3
"""
ibtzy TV Launcher: why are Home's titles old?  Read-only. Runs the same TMDB requests as Recs.fetchList (same filters, same
order), applies the same checks as tabPool/tabShown/homeShown, and prints every title with its date and the step it
came from. Then looks up some titles that Netflix/Prime Video Pakistan show right now, to see what TMDB knows of them.

    python3 tools/recs_diagnose.py            (from the project folder, with the TV connected to adb)
"""
import json, os, re, subprocess, sys, time, urllib.parse, urllib.request
from datetime import date, timedelta

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
props = {}
for line in open(os.path.join(ROOT, "config.properties"), encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
KEY = props.get("tmdb_key", "")
REGION = (props.get("tmdb_region") or "US").upper()
HIDE_ANIM = props.get("hide_animation", "false").lower() == "true"
HIDDEN = {s.strip().lower() for s in props.get("hide_services", "").split(",") if s.strip()}
if not KEY:
    sys.exit("No tmdb_key in config.properties")

SERVICES = [
    ("netflix", "netflix", "com.netflix.ninja"), ("netflix", "netflix", "com.netflix.mediaclient"),
    ("prime video", "prime", "com.amazon.amazonvideo.livingroom"), ("prime video", "prime", "com.amazon.avod.thirdpartyclient"),
    ("disney", "disney", "com.disney.disneyplus"), ("hulu", "hulu", "com.hulu.livingroomplus"),
    ("paramount", "paramount", "com.cbs.ott"), ("peacock", "peacock", "com.peacocktv.peacockandroid"),
    ("globoplay", "globoplay", "com.globo.globotv"), ("max", "max", "com.wbd.stream"), ("max", "max", "com.hbo.hbonow"),
    ("apple tv", "appletv", "com.apple.atve.androidtv.appletv"), ("apple tv", "appletv", "com.apple.atve.sony.appletv"),
    ("hotstar", "hotstar", "in.startv.hotstar"), ("hotstar", "hotstar", "in.startv.hotstartvonly"),
    ("jio", "hotstar", "com.jio.media.ondemand"), ("okko", "okko", "ru.more.play"),
    ("crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"), ("oneplay", "oneplay", "net.cme.voyo.cz.tvapp"),
]

try:
    pkgs = set(re.findall(r"package:(\S+)", subprocess.run(["adb", "shell", "pm", "list", "packages"],
                                                           capture_output=True, text=True, timeout=20).stdout))
except Exception as e:
    pkgs = set()
if not pkgs:
    sys.exit("adb couldn't list the TV's packages: connect it first (adb connect <tv-ip>:5555)")
installed = lambda p: p in pkgs


def service(name):
    n = name.lower()
    first = None
    for s in SERVICES:
        if not (re.search(r"\bmax\b", n) if s[0] == "max" else s[0] in n):
            continue
        if s[1] in HIDDEN:
            continue
        if installed(s[2]):
            return s
        first = first or s
    return first


calls = 0
def get(path):
    global calls
    calls += 1
    bearer = KEY.startswith("eyJ")
    url = "https://api.themoviedb.org/3" + path + ("" if bearer else ("&" if "?" in path else "?") + "api_key=" + urllib.parse.quote(KEY))
    req = urllib.request.Request(url, headers={"Accept": "application/json", **({"Authorization": "Bearer " + KEY} if bearer else {})})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(2); continue
            raise
    raise IOError("TMDB rate limit")


def day(offset):
    return (date.today() + timedelta(days=offset)).isoformat()


def age(d):
    try:
        return (date.today() - date.fromisoformat(d[:10])).days
    except Exception:
        return None


LANG = "en-US"
details_cache = {}
def details(kind, tid):
    k = (kind, tid)
    if k not in details_cache:
        details_cache[k] = get(f"/{kind}/{tid}?language={LANG}&append_to_response=watch/providers")
    return details_cache[k]


def on_service(d):
    """Like fetchDetails: the first installed service in REGION's flatrate list (Crunchyroll only judged in IN)."""
    flat = (((d.get("watch/providers") or {}).get("results") or {}).get(REGION) or {}).get("flatrate") or []
    for f in flat:
        s = service(f.get("provider_name", ""))
        if s and installed(s[2]) and not (s[1] == "crunchyroll" and REGION != "IN"):
            return s[1]
    return None


def when(kind, d):
    if kind == "movie":
        return d.get("release_date") or ""
    last = d.get("last_episode_to_air") or {}
    return last.get("air_date") or d.get("first_air_date") or ""


def pick(o, kind, step):
    if not o.get("backdrop_path"):
        return None
    if HIDE_ANIM and 16 in (o.get("genre_ids") or []):
        return None
    return {"id": o["id"], "kind": kind, "title": o.get("title" if kind == "movie" else "name"), "lang": o.get("original_language", ""),
            "step": step, "pop": o.get("popularity", 0), "listdate": o.get("release_date" if kind == "movie" else "first_air_date", "")}


print(f"Region {REGION}. Streaming apps on the TV: " + ", ".join(sorted({s[1] for s in SERVICES if installed(s[2])})) or "none")

# --- fetchList, step by step ---
provs = get(f"/watch/providers/movie?language={LANG}&watch_region={REGION}")["results"]
ids = {}
for o in provs:
    s = service(o.get("provider_name", ""))
    if s and installed(s[2]):
        ids[o["provider_id"]] = o["provider_name"]
print(f"TMDB's {REGION} providers used: " + ", ".join(f"{v} ({k})" for k, v in ids.items()))
common = (f"&language={LANG}&watch_region={REGION}&with_watch_monetization_types=flatrate&include_adult=false"
          f"&sort_by=popularity.desc&with_watch_providers=" + urllib.parse.quote("|".join(map(str, ids))) +
          ("&without_genres=16" if HIDE_ANIM else ""))

print("\n=== 1. TMDB trending this week (top 20 each): kept only if on one of your apps in " + REGION + " ===")
hot = {"movie": [], "tv": []}
dropped_hot = 0
for kind in ("movie", "tv"):
    for i, o in enumerate(get(f"/trending/{kind}/week?language={LANG}")["results"][:20]):
        p = pick(o, kind, "trending")
        if not p:
            continue
        d = details(kind, p["id"])
        svc = on_service(d)
        p["date"], p["app"] = when(kind, d), svc
        flat_any = sorted({c for c, v in ((d.get("watch/providers") or {}).get("results") or {}).items() if v.get("flatrate")})
        mark = "KEPT " if svc else "dropped"
        print(f"  {mark} #{i+1:<2} {kind:5} {p['title'][:38]:38} {p['date'] or '?':10}  {REGION}: {svc or '-':10} "
              f"(streams in {len(flat_any)} countries{', incl. IN' if 'IN' in flat_any else ''})")
        if svc:
            hot[kind].append(p)
        else:
            dropped_hot += 1

print(f"\n=== 2. Discover (popularity order): movies of the past 365 days, shows with an episode in the past 90 ===")
new = {"movie": [], "tv": []}
for kind, q in (("movie", f"/discover/movie?vote_count.gte=30&primary_release_date.gte={day(-365)}&primary_release_date.lte={day(0)}" + common),
                ("tv", f"/discover/tv?vote_count.gte=20&air_date.gte={day(-90)}&air_date.lte={day(0)}" + common)):
    for o in get(q)["results"]:
        p = pick(o, kind, "discover")
        if p:
            new[kind].append(p)

def key(p): return p["kind"] + ":" + str(p["id"])


def alternate(lst, seen, a, b, mx):
    for i in range(max(len(a), len(b))):
        if len(lst) >= mx: break
        if i < len(a) and key(a[i]) not in seen: seen.add(key(a[i])); lst.append(a[i])
        if i < len(b) and len(lst) < mx and key(b[i]) not in seen: seen.add(key(b[i])); lst.append(b[i])


def disc(kind, path, step):
    try:
        return [x for x in (pick(o, kind, step) for o in get(path)["results"]) if x]
    except Exception as e:
        print("  query failed:", e); return []


def take_turns(lists):
    out, seen = [], set()
    for i in range(max([len(l) for l in lists] or [0])):
        for l in lists:
            if i < len(l) and key(l[i]) not in seen: seen.add(key(l[i])); out.append(l[i])
    return out


def extra_movies(lst, seen):
    movies = sum(p["kind"] == "movie" for p in lst)
    more, page = list(new["movie"]), 2
    while movies < 22 and page <= 3:
        for m in more:
            if movies >= 22: break
            if key(m) not in seen:
                seen.add(key(m)); lst.append(dict(m, step="extra movies")); movies += 1
        if movies >= 22: break
        more = disc("movie", f"/discover/movie?vote_count.gte=30&primary_release_date.gte={day(-365)}&primary_release_date.lte={day(0)}" + common + f"&page={page}", "extra movies")
        page += 1


def build_old():
    seen, lst = set(), []
    alternate(lst, seen, hot["movie"], hot["tv"], 24)
    alternate(lst, seen, new["movie"], new["tv"], 24)
    extra_movies(lst, seen)
    for kind, q in (("movie", f"/discover/movie?with_original_language=hi&vote_count.gte=5&primary_release_date.gte={day(-365)}&primary_release_date.lte={day(0)}" + common),
                    ("tv", f"/discover/tv?with_original_language=hi&air_date.gte={day(-180)}&air_date.lte={day(0)}" + common)):
        added = 0
        for p in disc(kind, q, "bollywood"):
            if added < 12 and key(p) not in seen:
                seen.add(key(p)); lst.append(p); added += 1
    return lst, seen


base = (f"&language={LANG}&watch_region={REGION}&with_watch_monetization_types=flatrate&include_adult=false"
        f"&sort_by=popularity.desc" + ("&without_genres=16" if HIDE_ANIM else ""))
APP = {}
for pid, name in ids.items():
    s_ = service(name); APP[pid] = s_[1]


def tier_of(d):
    a = age(d)
    return 2 if a is None else 0 if a <= 7 else 1 if a <= 30 else 2


def tiered(rows, t, step):
    return [dict(p, tier=t, step=step) for p in rows]


def build_new():
    seen, lst = set(), []
    mq, sq = {}, {}
    for h in hot["movie"]: mq.setdefault(h["app"], []).append(dict(h, tier=tier_of(h["date"])))
    for h in hot["tv"]: sq.setdefault(h["app"], []).append(dict(h, tier=tier_of(h["date"])))
    for pid, app in APP.items():
        only = base + f"&with_watch_providers={pid}"
        m, t = mq.setdefault(app, []), sq.setdefault(app, [])
        m += tiered(disc("movie", f"/discover/movie?primary_release_date.gte={day(-7)}&primary_release_date.lte={day(0)}" + only, ""), 0, "this week " + app)
        t += tiered(disc("tv", f"/discover/tv?air_date.gte={day(-7)}&air_date.lte={day(0)}" + only, ""), 0, "this week " + app)
        m += tiered(disc("movie", f"/discover/movie?vote_count.gte=3&primary_release_date.gte={day(-30)}&primary_release_date.lte={day(-8)}" + only, ""), 1, "this month " + app)
        t += tiered(disc("tv", f"/discover/tv?vote_count.gte=3&air_date.gte={day(-30)}&air_date.lte={day(-8)}" + only, ""), 1, "this month " + app)
    alternate(lst, seen, take_turns(list(mq.values())), take_turns(list(sq.values())), 120)
    alternate(lst, seen, [dict(p) for p in new["movie"]], [dict(p) for p in new["tv"]], len(lst) + 16)
    extra_movies(lst, seen)
    for kind in ("movie", "tv"):
        week, month = [], []
        for pid, app in APP.items():
            only = base + f"&with_watch_providers={pid}&with_original_language=hi"
            if kind == "movie":
                week.append(tiered(disc(kind, f"/discover/movie?primary_release_date.gte={day(-7)}&primary_release_date.lte={day(0)}" + only, ""), 0, "this week bollywood"))
                month.append(tiered(disc(kind, f"/discover/movie?primary_release_date.gte={day(-30)}&primary_release_date.lte={day(-8)}" + only, ""), 1, "this month bollywood"))
            else:
                week.append(tiered(disc(kind, f"/discover/tv?air_date.gte={day(-7)}&air_date.lte={day(0)}" + only, ""), 0, "this week bollywood"))
                month.append(tiered(disc(kind, f"/discover/tv?air_date.gte={day(-30)}&air_date.lte={day(-8)}" + only, ""), 1, "this month bollywood"))
        older = disc(kind, (f"/discover/movie?with_original_language=hi&vote_count.gte=5&primary_release_date.gte={day(-365)}&primary_release_date.lte={day(0)}" if kind == "movie"
                            else f"/discover/tv?with_original_language=hi&air_date.gte={day(-180)}&air_date.lte={day(0)}") + common, "bollywood")
        added = 0
        for p in take_turns(week) + take_turns(month) + older:
            if added < 12 and key(p) not in seen:
                seen.add(key(p)); lst.append(p); added += 1
    return lst, seen


def detail_all(lst):
    for p in lst:
        if "date" not in p:
            d = details(p["kind"], p["id"])
            p["date"], p["app"] = when(p["kind"], d), on_service(d)


def franchise(t):
    t = (t or "").lower()
    c = t.find(":")
    if c < 0: c = t.find(" - ")
    return (t[:c] if c > 0 else t).strip()


def tab_pool(lst, kind):
    crunchy, others, fr = [], [], set()
    for p in lst:
        if p["kind"] != kind or not p["app"]: continue
        if franchise(p["title"]) in fr: continue
        fr.add(franchise(p["title"]))
        (crunchy if p["app"] == "crunchyroll" else others).append(p)
    cr = min(len(crunchy), max(3, 15 - len(others)))
    rest = min(len(others), 15 - cr)
    out = []
    for tier in (0, 1, 2):
        hindi = [p for p in others if p.get("tier", 2) == tier and p["lang"] == "hi"]
        west = [p for p in others if p.get("tier", 2) == tier and p["lang"] != "hi"]
        i = 0
        while len(out) < rest and (i < len(west) or i < len(hindi)):
            if i < len(west): out.append(west[i])
            if len(out) < rest and i < len(hindi): out.append(hindi[i])
            i += 1
    step = 0 if cr == 0 else max(1, (len(out) + cr) // cr)
    for i in range(cr):
        out.insert(min(len(out), i * step + min(2, step - 1)), crunchy[i])
    return out


def tab_shown(lst, kind):
    pool = tab_pool(lst, kind)
    if len(pool) <= 12: return pool
    start = (int(time.time() // 3600) * 5) % len(pool)
    return [pool[(start + i) % len(pool)] for i in range(12)]


def home_of(lst):
    """Home: freshest first, movies and shows taking turns within each tier (this week's rotate hourly when > 12)."""
    ms, ss = tab_pool(lst, "movie"), tab_pool(lst, "tv")
    allp, hs, week = [], set(), 0
    for tier in (0, 1, 2):
        m = [p for p in ms if p.get("tier", 2) == tier]
        t = [p for p in ss if p.get("tier", 2) == tier]
        for i in range(max(len(m), len(t))):
            if i < len(m) and key(m[i]) not in hs: hs.add(key(m[i])); allp.append(m[i])
            if i < len(t) and key(t[i]) not in hs: hs.add(key(t[i])); allp.append(t[i])
        if tier == 0: week = len(allp)
    if week > 12:
        start = (int(time.time() // 3600) * 5) % week
        allp = [allp[(start + i) % week] for i in range(week)]
    return allp[:12]


def home_old(lst):
    ms, ss = tab_shown(lst, "movie"), tab_shown(lst, "tv")
    home, hs = [], set()
    for i in range(max(len(ms), len(ss))):
        if len(home) >= 12: break
        if i < len(ms) and key(ms[i]) not in hs: hs.add(key(ms[i])); home.append(ms[i])
        if len(home) < 12 and i < len(ss) and key(ss[i]) not in hs: hs.add(key(ss[i])); home.append(ss[i])
    return home


def show(title, rows):
    ages = [a for a in (age(p["date"]) for p in rows) if a is not None]
    print(f"\n=== {title}: {len(rows)} titles; newest {min(ages) if ages else '?'} days old, median "
          f"{sorted(ages)[len(ages)//2] if ages else '?'} days, {sum(a > 180 for a in ages)} older than 6 months ===")
    for p in rows:
        a = age(p["date"])
        print(f"  {p['kind']:5} {p['title'][:40]:40} {p['date'] or '?':10} {('%d d' % a) if a is not None else '':>7}  "
              f"{p['lang']:3} {p['app'] or '-':9} from {p['step']}")




def summary(name, rows):
    ages = sorted(a for a in (age(p["date"]) for p in rows) if a is not None)
    apps = {}
    for p in rows: apps[p["app"]] = apps.get(p["app"], 0) + 1
    return (f"{name:14} median {ages[len(ages)//2] if ages else '?':>3} days, {sum(a <= 7 for a in ages):2} out this week, {sum(a <= 30 for a in ages):2} this month, "
            f"{sum(a > 180 for a in ages):2} older than 6 months; " + ", ".join(f"{k} {v}" for k, v in apps.items()))


old_lst, _ = build_old(); detail_all(old_lst)
new_lst, seen = build_new(); detail_all(new_lst)
print("\n=== BEFORE (the app now) vs AFTER (the fix) ===")
for label, lst in (("BEFORE", old_lst), ("AFTER", new_lst)):
    print(label)
    print("  " + summary("Home (12)", home_old(lst) if label == "BEFORE" else home_of(lst)))
    print("  " + summary("Movies (15)", tab_pool(lst, "movie")))
    print("  " + summary("Shows (15)", tab_pool(lst, "tv")))
show("AFTER: HOME this hour", home_of(new_lst))
show("AFTER: Movies pool (15)", tab_pool(new_lst, "movie"))
show("AFTER: Shows pool (15)", tab_pool(new_lst, "tv"))

print("\n=== 3. Titles Netflix/Prime Video Pakistan show today: what TMDB knows ===")
for q in ["Gajab Raat", "Madam Inspector", "Chaar Yaar", "Neagley", "Dupahiya", "Young Sherlock", "Chumbak",
          "The Terminal List", "Lupin", "Mirzapur", "Roadies"]:
    r = get(f"/search/multi?language={LANG}&query=" + urllib.parse.quote(q))["results"]
    r = [x for x in r if x.get("media_type") in ("movie", "tv")][:1]
    if not r:
        print(f"  {q:20} not on TMDB"); continue
    x = r[0]; kind = x["media_type"]
    d = details(kind, x["id"])
    res = (d.get("watch/providers") or {}).get("results") or {}
    fl = lambda c: ",".join(f["provider_name"] for f in (res.get(c) or {}).get("flatrate") or []) or "-"
    in_list = "in AFTER list" if (kind + ":" + str(x["id"])) in seen else "NOT in list"
    print(f"  {q:20} {kind:5} {when(kind, d) or '?':10} pop {x.get('popularity', 0):6.1f}  {REGION}: {fl(REGION)[:28]:28} IN: {fl('IN')[:28]:28} {in_list}")

print(f"\n{calls} TMDB requests.")
