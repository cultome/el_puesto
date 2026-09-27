#!/usr/bin/env python3
"""
Prototipo del parser de posiciones de PILOTOS de Fórmula E desde las páginas públicas de
www.fiaformulae.com (sin API, sin llave). Solo lee archivos ya descargados o, con --fetch,
los baja con el User-Agent del proyecto y 1 s entre peticiones.

Fuentes (2 peticiones por corrida; 3 si se quiere cotejar fechas por día):
  1. /en/results-and-standings?tab=drivers&season=<N>   tabla vigente (HTML) + "Season Grid"
     (props JSON en el flight de Next: puntos/posición/estado de CADA piloto en CADA fecha)
  2. /en/drivers                                         número de coche por slug (parrilla ACTUAL)
  3. /en/results-and-standings?season=<N>                (opcional) calendario del sitio con
                                                         raceDate por fecha, para cotejar días

Temporada del sitio: N = año inicial - 2013 ("2025-26" -> 12, "2026-27" -> 13).

Uso:
  python3 fe_parser.py --label 2025-26 --round 17 --race-day 2026-08-16 [--fetch]
  python3 fe_parser.py --standings 02_drivers_s12.html --drivers 06_drivers_list.html \
                       --calendar 01_results-and-standings.html --label 2025-26 --round 17 --race-day 2026-08-16
"""
import argparse, datetime as dt, html.parser, json, re, sys, time, unicodedata, urllib.request

UA = "el-puesto-backend (ingesta de posiciones; https://elpuesto.app)"
BASE = "https://www.fiaformulae.com"

# ——— HTTP (solo con --fetch) ———
_last = [0.0]
def get(url):
    wait = 1.0 - (time.time() - _last[0])
    if wait > 0: time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html"})
    with urllib.request.urlopen(req, timeout=30) as r:
        _last[0] = time.time()
        if r.status != 200: raise RuntimeError(f"HTTP {r.status} en {url}")
        body = r.read(5 * 1024 * 1024 + 1)
        if len(body) > 5 * 1024 * 1024: raise RuntimeError("respuesta > 5 MB")
        if "challenge" in (r.headers.get("cf-mitigated") or ""): raise RuntimeError("reto anti-bots: no se evade")
        return body.decode("utf-8")

# ——— Flight de Next (self.__next_f.push) ———
PUSH = "self.__next_f.push("
def json_end(s, start):
    if start >= len(s) or s[start] not in "{[": return None
    depth = 0; ins = False; esc = False
    for k in range(start, len(s)):
        c = s[k]
        if ins:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': ins = False
            continue
        if c == '"': ins = True
        elif c in "{[": depth += 1
        elif c in "}]":
            depth -= 1
            if depth == 0: return k + 1
    return None

def flight(html):
    out = []; i = html.find(PUSH)
    while i >= 0:
        st = i + len(PUSH); en = json_end(html, st)
        if en is None: break
        ch = json.loads(html[st:en])
        if len(ch) > 1 and isinstance(ch[1], str): out.append(ch[1])
        i = html.find(PUSH, en)
    return "".join(out)

def value_after(fl, key):
    i = fl.find(key)
    if i < 0: return None
    i += len(key); e = json_end(fl, i)
    return json.loads(fl[i:e]) if e else None

def grid_rows(fl):
    """Props de cada fila del "Season Grid": {entity:'driver', position, name, points, media:{href}, rounds:[...]}."""
    g = value_after(fl, '"gridPanel":')
    if g is None: return None
    rows = []
    def walk(n):
        if isinstance(n, list):
            if len(n) == 4 and n[0] == "$" and isinstance(n[3], dict):
                if n[3].get("entity") == "driver": rows.append(n[3])
                walk(n[3].get("children"))
            else:
                for x in n: walk(x)
    walk(g)
    return rows

def season_items(fl):
    """Selector de temporadas: [{id:'13', text:'Season 13', active:bool}, ...]."""
    m = re.search(r'"seasonItems":(\[)', fl)
    if not m: return []
    st = m.start(1); return json.loads(fl[st:json_end(fl, st)])

def calendar(fl):
    """Fechas del sitio: [{roundNum, raceDate:'2026-08-16', location, countryCode, key}, ...]."""
    m = re.search(r'"rounds":(\[\{"key")', fl)
    if not m: return []
    st = m.start(1); return json.loads(fl[st:json_end(fl, st)])

# ——— HTML de la tabla (data-testid estables; las clases llevan hash) ———
class StandingsTable(html.parser.HTMLParser):
    """Filas de <tr data-testid="standings-row-driver">: pos (th), nombre, slug, equipo, total."""
    def __init__(self):
        super().__init__(); self.rows = []; self.cur = None; self.cell = None; self.buf = []; self.cls = []
        self.caption = None; self.in_caption = False; self.cap_buf = []
        self.empty_notice = False
    def handle_starttag(self, tag, attrs):
        a = dict(attrs); cls = a.get("class") or ""
        if tag == "caption": self.in_caption = True; self.cap_buf = []
        if tag == "tr" and a.get("data-testid") == "standings-row-driver":
            self.cur = {"pos": None, "name": None, "slug": None, "team": None, "teamSlug": None, "points": None, "tlaNation": None}
        if self.cur is None: return
        self.cls.append((tag, cls))
        if tag == "a" and a.get("href", "").startswith("/en/drivers/"): self.cur["slug"] = a["href"].rsplit("/", 1)[1]
        if tag == "a" and a.get("href", "").startswith("/en/teams/"): self.cur["teamSlug"] = a["href"].rsplit("/", 1)[1]
        self.buf = []
    def handle_endtag(self, tag):
        if tag == "caption" and self.in_caption:
            self.in_caption = False; self.caption = "".join(self.cap_buf).strip()
        if self.cur is None: return
        text = "".join(self.buf).strip()
        tag_, cls = self.cls.pop() if self.cls else (tag, "")
        if "standingsRow__position" in cls and tag == "th": self.cur["pos"] = int(text)
        elif "standingsRow__nameLabel" in cls: self.cur["name"] = self.cur["name"] or text
        elif tag == "a" and self.cur["slug"] and not self.cur["name"] and "standingsRow__nameLabel" in (self.cls[-1][1] if self.cls else ""):
            self.cur["name"] = text
        elif "standingsRow__nationCode" in cls: self.cur["tlaNation"] = text
        elif "standingsRow__teamLine" in cls: self.cur["team"] = text
        elif "standingsRow__points" in cls and tag == "td": self.cur["points"] = int(text)
        if tag == "tr":
            self.rows.append(self.cur); self.cur = None; self.cls = []
    def handle_data(self, d):
        if self.in_caption: self.cap_buf.append(d)
        if self.cur is not None: self.buf.append(d)
        if "There are no standings for this season" in d: self.empty_notice = True

def parse_standings_html(html):
    p = StandingsTable(); p.feed(html); return p

class DriverCards(html.parser.HTMLParser):
    """/en/drivers: slug -> número del coche (parrilla ACTUAL) y la leyenda de temporada."""
    def __init__(self):
        super().__init__(); self.numbers = {}; self.slug = None; self.in_num = False
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "a" and a.get("href", "").startswith("/en/drivers/"): self.slug = a["href"].rsplit("/", 1)[1]
        if tag == "span" and "driverCard__number" in (a.get("class") or ""): self.in_num = True
    def handle_endtag(self, tag):
        if tag == "span": self.in_num = False
        if tag == "a": self.slug = None
    def handle_data(self, d):
        if self.in_num and self.slug and d.strip(): self.numbers[self.slug] = d.strip()

def drivers_season_label(html):
    m = re.search(r"Season (\d+) - (\d{4})/(\d{2})", html)
    return (int(m.group(1)), f"{m.group(2)}-{m.group(3)}") if m else (None, None)

def slugify(name):
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")

def site_season(label):
    """'2025-26' -> 12 (S1 = 2014-15)."""
    m = re.fullmatch(r"(\d{4})-(\d{2}|\d{4})", label.strip())
    if not m: raise ValueError(f"etiqueta de temporada inesperada: {label}")
    return int(m.group(1)) - 2013

# ——— Lógica de la fuente ———
class NotReady(Exception): pass

def raced(rows, rn):
    """La fecha rn tiene resultado de CARRERA (no solo los 3 puntos de la pole de la qualy)."""
    cells = [r2 for r in rows for r2 in r["rounds"] if r2["roundNum"] == rn]
    winner = any(c.get("position") == 1 for c in cells)
    classified = sum(1 for c in cells if c.get("position") is not None)
    return winner and classified >= 10

def ingest(standings_html, drivers_html, label, round_no, race_day, rounds_in_calendar=None, calendar_html=None):
    n = site_season(label)
    fl = flight(standings_html)
    items = season_items(fl)
    active = next((it for it in items if it.get("active")), None)
    if not active or active.get("id") != str(n):
        raise RuntimeError(f"el sitio no muestra la temporada {n} ({label}); activa: {active}")
    tbl = parse_standings_html(standings_html)
    if tbl.caption and tbl.caption != f"Driver Standings, season {n}":
        raise RuntimeError(f"la tabla es de otra temporada: {tbl.caption!r}")
    grid = grid_rows(fl) or []
    if tbl.empty_notice or not tbl.rows:
        raise NotReady(f"el sitio aún no publica la tabla de la temporada {n}")
    # Calendario del sitio (opcional): cotejar la fecha por día (±1).
    if calendar_html is not None:
        cal = calendar(flight(calendar_html))
        match = [c for c in cal if abs((dt.date.fromisoformat(c["raceDate"]) - race_day).days) <= 1]
        if not match: raise RuntimeError(f"el sitio no tiene una fecha el {race_day} (fecha {round_no} nuestra)")
        if rounds_in_calendar and len(cal) != rounds_in_calendar:
            raise RuntimeError(f"el sitio tiene {len(cal)} fechas y nuestro calendario {rounds_in_calendar}")
        site_round = next((c["roundNum"] for c in match if c["roundNum"] == round_no), match[0]["roundNum"])
        if site_round != round_no:
            raise RuntimeError(f"la fecha del {race_day} es la {site_round} del sitio y la {round_no} nuestra")
    reflected = [rn for rn in sorted({c["roundNum"] for r in grid for c in r["rounds"]}) if raced(grid, rn)]
    through = max(reflected) if reflected else 0
    if through < round_no:
        raise NotReady(f"el sitio aún refleja hasta la fecha {through}; se pidió la {round_no}")
    if through > round_no:
        raise RuntimeError(f"el sitio solo publica la tabla vigente y ya va en la fecha {through}: pide la última fecha terminada")
    # Coherencia tabla (HTML) vs. Season Grid (props): mismo orden, mismo total = suma por fecha.
    if len(grid) != len(tbl.rows): raise NotReady(f"tabla ({len(tbl.rows)}) y grid ({len(grid)}) con distinto número de filas")
    for i, (t, g) in enumerate(zip(tbl.rows, grid)):
        if t["name"] != g["name"] or t["points"] != g["points"] or t["pos"] != g["position"]:
            raise NotReady(f"fila {i+1}: la tabla y el grid no coinciden ({t['name']} {t['points']} / {g['name']} {g['points']})")
        s = sum((c.get("points") or 0) for c in g["rounds"] if c["roundNum"] <= through)
        if s != g["points"]:
            raise NotReady(f"{g['name']}: la suma por fecha ({s}) no da el total ({g['points']})")
    # Números: solo si /en/drivers es de ESTA temporada (muestra la parrilla actual).
    numbers = {}
    if drivers_html is not None:
        dn, dl = drivers_season_label(drivers_html)
        if dn == n:
            dc = DriverCards(); dc.feed(drivers_html); numbers = dc.numbers
    drivers, standings = [], []
    prev = None
    for i, t in enumerate(tbl.rows):
        if prev is not None and t["points"] > prev: raise RuntimeError("la tabla no viene ordenada por puntos")
        prev = t["points"]
        ref = t["slug"] or slugify(t["name"])
        drivers.append({"name": t["name"], "team": t["team"], "numberText": numbers.get(ref), "ref": ref})
        standings.append({"pos": t["pos"], "points": t["points"], "driverRef": ref})
    if [s["pos"] for s in standings] != list(range(1, len(standings) + 1)):
        raise RuntimeError("posiciones no consecutivas")
    return through, drivers, standings

def reconstruct(grid, x):
    """Tabla TRAS la fecha x armada con el Season Grid: suma de puntos por fecha <= x y desempate
    por conteo (más 1.os, luego más 2.os...). Reprodujo el orden oficial en S9-S12 (empates incluidos).
    Opción para cuando el sitio ya va más allá de la fecha pedida (solo publica la vigente)."""
    rows = []
    for r in grid:
        cells = [c for c in r["rounds"] if c["roundNum"] <= x]
        pts = sum((c.get("points") or 0) for c in cells)
        cnt = [0] * 64
        for c in cells:
            if c.get("position"): cnt[c["position"] - 1] += 1
        rows.append((pts, cnt, r))
    rows.sort(key=lambda t: (-t[0], [-k for k in t[1]]))
    return [(i + 1, t[0], t[2]["name"]) for i, t in enumerate(rows)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True); ap.add_argument("--round", type=int, required=True)
    ap.add_argument("--race-day", required=True); ap.add_argument("--rounds", type=int)
    ap.add_argument("--standings"); ap.add_argument("--drivers"); ap.add_argument("--calendar")
    ap.add_argument("--fetch", action="store_true")
    a = ap.parse_args()
    n = site_season(a.label)
    if a.fetch:
        st = get(f"{BASE}/en/results-and-standings?tab=drivers&season={n}")
        dr = get(f"{BASE}/en/drivers")
        ca = get(f"{BASE}/en/results-and-standings?season={n}") if a.rounds else None
    else:
        st = open(a.standings, encoding="utf-8").read()
        dr = open(a.drivers, encoding="utf-8").read() if a.drivers else None
        ca = open(a.calendar, encoding="utf-8").read() if a.calendar else None
    try:
        through, drivers, standings = ingest(st, dr, a.label, a.round, dt.date.fromisoformat(a.race_day), a.rounds, ca)
    except NotReady as e:
        print("NOT READY:", e); sys.exit(2)
    by = {d["ref"]: d for d in drivers}
    print(f"Ready(throughRound={through}) {len(standings)} filas")
    for s in standings:
        d = by[s["driverRef"]]
        print(f'{s["pos"]:>3} {d["numberText"] or "-":>3} {d["name"]:<28} {d["team"]:<32} {s["points"]:>4}  ref={d["ref"]}')

if __name__ == "__main__":
    main()
