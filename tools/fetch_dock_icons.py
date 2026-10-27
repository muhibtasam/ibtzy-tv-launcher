#!/usr/bin/env python3
"""Downloads the current official app icons (Apple App Store artwork, 512 px PNG, full square) into app/assets/dock/.
The dock rounds every icon the same way, so they all match. Named by the dock key: the shelf_apps name, lower case,
no spaces ("Prime Video" -> primevideo.png). Apps not listed here use the icon of the app installed on the TV.
Run by install-on-tv.sh before each build, so the icons follow the apps' redesigns; offline, the last ones stay."""
import json, os, subprocess, sys

# dock key -> App Store id (checked: these are the official apps)
APPS = {
    "netflix": 363590051,
    "youtube": 544007664,
    "primevideo": 545519333,
    "crunchyroll": 329913454,
    "spotify": 324684580,
    "youtubemusic": 1017492454,
    "nordvpn": 905953485,
    "localsend": 1661733229,
}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "assets", "dock")

def curl(url):
    return subprocess.run(["curl", "-fsSL", "--max-time", "20", url], capture_output=True, check=True).stdout

def main():
    os.makedirs(OUT, exist_ok=True)
    ids = ",".join(str(i) for i in APPS.values())
    try:
        results = json.loads(curl("https://itunes.apple.com/lookup?id=" + ids))["results"]
    except Exception as e:
        print("Dock icons: offline or App Store unreachable, keeping the current ones (%s)" % e)
        return
    by_id = {r.get("trackId"): r for r in results}
    for key, app_id in APPS.items():
        r = by_id.get(app_id)
        url = r and r.get("artworkUrl512")
        if not url:
            print("  %-13s not found, keeping the current icon" % key)
            continue
        url = url.rsplit("/", 1)[0] + "/512x512bb.png"
        try:
            data = curl(url)
        except Exception:
            print("  %-13s download failed, keeping the current icon" % key)
            continue
        with open(os.path.join(OUT, key + ".png"), "wb") as f:
            f.write(data)
        print("  %-13s %s" % (key, r.get("trackName")))
    print("Dock icons updated in app/assets/dock/")

if __name__ == "__main__":
    sys.exit(main())
