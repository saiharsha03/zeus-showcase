"""Aeolia page shots: BetweenWeather's bundled page in headless Chrome, with a stand-in for the
Android bridge fed from the saved API samples (app/src/test/resources/aeolia), at the Fold's cover and
inner widths, dark and light, with Zeus's tokens. Writes app/build/shots/aeolia-*.png.

Robolectric has no WebView, so this is the page as Chrome draws it, not a device capture. Radar tiles
load from the network if this laptop can reach the tile hosts.

    python tools/aeolia_shots.py
"""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/aeolia"
FIX = ROOT / "app/src/test/resources/aeolia"
OUT = ROOT / "app/build/shots"
WORK = ROOT / "app/build/aeolia-harness"
CHROME = [
    pathlib.Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    pathlib.Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
]

# Zeus's tokens (ui/Theme.kt), as AeoliaHost.tokens() sends them.
DARK = {"dark": True, "bg": "#15120F", "bg2": "#211D19", "bg3": "#2A251F", "ink": "#F2ECE2", "ink2": "#BDB4A7", "ink3": "#A0968A",
        "accent": "#E8A33D", "accentInk": "#E8A33D", "alert": "#EE8070"}
LIGHT = {"dark": False, "bg": "#F3EEE5", "bg2": "#EAE4DA", "bg3": "#E6DFD3", "ink": "#1C1814", "ink2": "#4F463D", "ink3": "#62594E",
         "accent": "#E8A33D", "accentInk": "#7A4C0B", "alert": "#A33427"}

# Width x height in CSS px (dp). Cover: 384 dp wide, as RenderShots; inner: the rail takes 88 of ~868.
SIZES = {"cover": (384, 3300), "inner": (780, 2300)}
MIN_WINDOW = 500


def fixture(name):
    return json.loads((FIX / name).read_text(encoding="utf-8"))


def mock(tokens):
    forecast = fixture("forecast_buffalo.json")
    alerts = fixture("alerts_severe.json")
    # One warning, as if it were for Buffalo, so the alerts card has something to show.
    storm = next(f for f in alerts["features"] if f["properties"]["event"] == "Winter Storm Warning")
    storm["properties"]["areaDesc"] = "Northern Erie"
    nws = {"type": "FeatureCollection", "features": [storm]}
    radar = {"rainviewer": fixture("rainviewer.json"), "iem": {"n0q": fixture("iem_n0q.json"), "hrrr": fixture("iem_hrrr.json")}}
    sel = {"lat": 42.8864, "lon": -78.8784, "name": "Buffalo", "country": "US"}
    data = {"forecast": forecast, "nws": nws, "air": fixture("air_buffalo.json"), "radar": radar, "sel": sel, "tokens": tokens}
    return """
window.__D=%s;
(()=>{const D=window.__D,at=(f,x)=>setTimeout(()=>window[f]&&window[f](x),30);
window.Weather={
 saved:()=>JSON.stringify(D.sel),cached:()=>JSON.stringify(D.forecast),cachedAlerts:()=>'null',getPreferences:()=>'{}',savePreferences:()=>{},
 forecast:(lat,lon)=>at('renderWeather',{...D.forecast,selected_lat:lat,selected_lon:lon}),
 details:(lat,lon)=>{at('renderAlerts',{...D.nws,selected_lat:lat,selected_lon:lon,fetched_at:Date.now()});at('renderAir',{...D.air,selected_lat:lat,selected_lon:lon})},
 radar:()=>at('radarReady',D.radar),search:()=>{},holidays:()=>at('holidaysUnavailable',null),
 notifications:()=>true,setNotifications:()=>{},places:()=>'null',hereAllowed:()=>true,locate:()=>{},openOfficial:()=>{},saveQuip:()=>{},haptic:()=>{},
 quip:(f,t,id)=>at('nanoQuip',{id,text:'Clear and cold. The sky is calm, which is exactly what it wants you to think.',status:'ready',reason:null}),
 nanoStatus:()=>at('nanoStatus','ready')};
addEventListener('load',()=>setTimeout(()=>window.applyZeus&&applyZeus(D.tokens),10));})();
""" % json.dumps(data)


def harness(name, tokens):
    html = (ASSETS / "index.html").read_text(encoding="utf-8")
    head_end = html.index(">", html.index("<head")) + 1
    # file:// has no 'self' origin, so the CSP is dropped here; the app keeps it.
    import re
    html = re.sub(r'<meta http-equiv="Content-Security-Policy"[^>]*>', "", html)
    html = html[:head_end] + '<base href="%s/">' % ASSETS.as_uri() + "<script>" + mock(tokens) + "</script>" + html[head_end:]
    WORK.mkdir(parents=True, exist_ok=True)
    p = WORK / f"{name}.html"
    p.write_text(html, encoding="utf-8")
    return p


def main():
    chrome = next((c for c in CHROME if c.exists()), None)
    if chrome is None:
        sys.exit("No Chrome or Edge found")
    OUT.mkdir(parents=True, exist_ok=True)
    for size, (w, h) in SIZES.items():
        for theme, tokens in (("dark", DARK), ("light", LIGHT)):
            name = f"aeolia-{size}-{theme}"
            page = harness(name, tokens)
            # Headless Chrome won't make a window narrower than 500 px, so a narrow page goes in an
            # iframe of the exact width (its own viewport) and the shot is cropped to it.
            win = max(w, MIN_WINDOW)
            if w < MIN_WINDOW:
                frame = WORK / f"{name}-frame.html"
                frame.write_text(f'<!doctype html><body style="margin:0;background:#000"><iframe src="{page.name}" style="border:0;display:block;width:{w}px;height:{h}px"></iframe>', encoding="utf-8")
                page = frame
            out = OUT / f"{name}.png"
            subprocess.run([
                str(chrome), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--mute-audio",
                "--allow-file-access-from-files", "--autoplay-policy=no-user-gesture-required", "--force-device-scale-factor=2",
                f"--window-size={win},{h}", "--virtual-time-budget=9000", f"--screenshot={out}", page.as_uri(),
            ], check=True, capture_output=True, timeout=120)
            if win != w:
                from PIL import Image
                Image.open(out).crop((0, 0, w * 2, h * 2)).save(out)
            print(out)


if __name__ == "__main__":
    main()
