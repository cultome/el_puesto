#!/usr/bin/env python3
"""
Prototipo: tabla de PILOTOS del WRC (WRC, WRC2, WRC3, Junior WRC) desde los JSON públicos que
descarga el widget de https://www.wrc.com/en/results-and-standings/championship-standings
(script https://p-p.redbull.com/rb-wrccom-lintegration-yv-prod/bundle.js → fetch() sin
cabeceras ni llave a .../api/*.json).

Uso:
  python3 wrc_standings.py --year 2026 --category wrc2 --round 12 --race-day 2026-09-13 [--numbers] [--no-live]
  --category: wrc | wrc2 | wrc3 | jwrc
  --numbers:  además busca número de coche y equipo en las listas de inscritos (2 peticiones por rally)
  --offline DIR: lee las respuestas guardadas en DIR (mismos nombres que --save) en vez de la red
  --save DIR: guarda cada respuesta cruda

Salida: JSON {status: ready|not-ready|error, throughRound, drivers[], standings[], requests, notes[]}
con el mismo modelo que el backend: Driver(name, team, numberText, ref) y Standing(pos, points, driverRef).
"""
import argparse, datetime as dt, gzip, json, os, re, sys, time, unicodedata, urllib.request, urllib.error

UA = "el-puesto-backend (ingesta de posiciones; https://elpuesto.app)"
BASE = "https://p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api"
SEASON_NAME = "World Rally Championship"
# Categoría de la app → nombre EXACTO de la tabla de pilotos en la fuente (los ids cambian cada año).
CHAMPIONSHIP = {
    "wrc": "FIA World Rally Championship for Drivers",
    "wrc2": "FIA WRC2 Championship for Drivers",
    "wrc3": "FIA WRC3 Championship for Drivers",
    "jwrc": "FIA Junior WRC Championship for Drivers",
}


class NotReady(Exception):
    pass


class Http:
    def __init__(self, offline=None, save=None):
        self.offline, self.save, self.requests, self.logical, self._last = offline, save, 0, 0, 0.0

    @staticmethod
    def fname(path):
        return re.sub(r"[^A-Za-z0-9._-]+", "_", path.strip("/")) or "root"

    def get(self, path):
        name = self.fname(path)
        self.logical += 1
        cached = os.path.join(self.offline or self.save or "", name)
        if (self.offline or self.save) and os.path.exists(cached):  # --save DIR también sirve de caché
            with open(cached, "rb") as f:
                return json.loads(f.read())
        if self.offline:
            raise FileNotFoundError(cached)
        wait = 1.0 - (time.time() - self._last)  # cortesía: ~1 petición por segundo
        if wait > 0:
            time.sleep(wait)
        req = urllib.request.Request(BASE + path, headers={"User-Agent": UA, "Accept": "application/json", "Accept-Encoding": "gzip"})
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read(5 * 1024 * 1024 + 1)
            if len(raw) > 5 * 1024 * 1024:
                raise RuntimeError("respuesta mayor a 5 MB")
            if r.headers.get("Content-Encoding", "") == "gzip":
                raw = gzip.decompress(raw)
            ctype = r.headers.get("Content-Type", "")
        self._last = time.time()
        self.requests += 1
        if "json" not in ctype:  # un reto anti-bots llegaría como HTML: no se evade, se falla
            raise RuntimeError(f"la fuente no respondió JSON en {path} ({ctype})")
        if self.save:
            os.makedirs(self.save, exist_ok=True)
            with open(os.path.join(self.save, name), "wb") as f:
                f.write(raw)
        return json.loads(raw)


def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s or "") if unicodedata.category(c) != "Mn").casefold().strip()


def proper_surname(upper):
    """'EVANS' → 'Evans', 'MCERLEAN' → 'McErlean', 'TÜRKKAN' → 'Türkkan', 'TEN BRINKE' → 'Ten Brinke'."""
    def word(w):
        parts = []
        for p in w.split("-"):
            p = p.lower().capitalize()
            if len(p) > 2 and p.startswith("Mc"):
                p = "Mc" + p[2:].capitalize()
            parts.append(p)
        return "-".join(parts)
    return " ".join(word(w) for w in upper.split())


def proper_team(upper):
    """'TOYOTA GAZOO RACING WRT' → 'Toyota Gazoo Racing WRT'; 'M-SPORT FORD …' → 'M-Sport Ford …' (≤3 letras = sigla)."""
    def part(p):
        if any(c.isdigit() for c in p) or (len(p) <= 3 and p.lower() not in {"of", "the", "and"}):
            return p  # sigla o con dígitos: WRT, HF, WRT2, 2C, JML
        return p.lower().capitalize()
    return " ".join("-".join(part(p) for p in w.split("-")) for w in upper.split())


def fetch(http, year, category, rnd, race_day, numbers=False, check_live=True):
    notes = []
    name = CHAMPIONSHIP[category]
    # 1. Temporada por AÑO (no la "vigente"): nombre exacto + año.
    seasons = [s for s in http.get("/seasons.json") if s.get("name") == SEASON_NAME and s.get("year") == year]
    if len(seasons) != 1:
        raise RuntimeError(f"wrc.com no tiene la temporada {year} ({len(seasons)} coincidencias)")
    season_id = seasons[0]["seasonId"]
    # 2. Tabla de la categoría (id por nombre) y rally que corresponde a NUESTRA fecha (por día).
    sd = http.get(f"/season-detail.json?seasonId={season_id}")
    champs = [c for c in sd["championships"] if c.get("name") == name]
    if len(champs) != 1:
        raise RuntimeError(f"wrc.com cambió sus campeonatos: no está «{name}» en {year}")
    cid = champs[0]["championshipId"]
    events = sorted(sd["seasonRounds"], key=lambda r: r["order"])
    match = [r for r in events if dt.date.fromisoformat(r["event"]["startDate"]) - dt.timedelta(days=1) <= race_day <= dt.date.fromisoformat(r["event"]["finishDate"]) + dt.timedelta(days=1)]
    if len(match) != 1:
        raise RuntimeError(f"wrc.com no tiene un rally que coincida con el {race_day} (fecha {rnd} de nuestro calendario)")
    target = match[0]
    tev, tord, tname = target["eventId"], target["order"], target["event"]["name"]

    # 3. Tabla + resultados por rally.
    overall = http.get(f"/championship-overall-results.json?championshipId={cid}&seasonId={season_id}")
    rows = sorted(overall["entryResults"], key=lambda r: r["overallPosition"])
    if not rows:
        raise NotReady(f"wrc.com aún no publica «{name}» {year}")
    order_of = {r["eventId"]: r["order"] for r in events}

    def ran(ev):
        return any(rr["eventId"] == ev and rr["status"] != "DidNotEnter" for r in rows for rr in r["roundResults"])

    if not ran(tev):
        raise NotReady(f"wrc.com aún no refleja «{tname}» (fecha {rnd}) en {name}")
    unpublished = [rr for r in rows for rr in r["roundResults"] if rr["eventId"] == tev and rr.get("publishedStatus") != "Published"]
    if unpublished:
        raise NotReady(f"«{tname}» todavía no está publicado ({unpublished[0].get('publishedStatus')}): se reintenta")
    ahead = sorted({order_of.get(rr["eventId"], 0) for r in rows for rr in r["roundResults"] if rr["status"] != "DidNotEnter" and order_of.get(rr["eventId"], 0) > tord})
    if ahead:
        raise RuntimeError(f"wrc.com ya refleja rallies posteriores (orden {ahead}) a la fecha {rnd}: la tabla es la vigente; pide la última fecha terminada")
    if check_live:
        live = http.get("/live-events.json")
        if any((e.get("eventId") if isinstance(e, dict) else e) == tev for e in live):
            raise NotReady(f"«{tname}» sigue en vivo en wrc.com")
        if live:
            notes.append(f"live-events no vacío: {json.dumps(live)[:200]}")

    # Coherencia: total = suma de lo no descartado; posiciones 1..N; puntos no crecientes.
    for r in rows:
        s = sum(rr["totalPoints"] for rr in r["roundResults"] if not rr.get("dropped"))
        if s != r["overallPoints"]:
            raise RuntimeError(f"fila {r['overallPosition']}: total {r['overallPoints']} ≠ suma por rally {s}")
    if [r["overallPosition"] for r in rows] != list(range(1, len(rows) + 1)):
        raise RuntimeError("posiciones no contiguas")
    pts = [r["overallPoints"] for r in rows]
    if any(b > a for a, b in zip(pts, pts[1:])):
        raise RuntimeError("tabla no ordenada por puntos")
    tot = sum(rr["totalPoints"] for r in rows for rr in r["roundResults"] if rr["eventId"] == tev)
    notes.append(f"puntos repartidos en «{tname}»: {tot}" + (" (WRC completo = 100 rally + 15 Super Sunday + 15 Power Stage)" if category == "wrc" else ""))

    # 4. Nombres (join por championshipEntryId).
    detail = http.get(f"/championship-detail.json?championshipId={cid}&seasonId={season_id}")
    ents = {e["championshipEntryId"]: e for e in detail["championshipEntries"]}
    missing = [r["championshipEntryId"] for r in rows if r["championshipEntryId"] not in ents]
    if missing:
        raise NotReady(f"la tabla trae entradas sin piloto en championship-detail: {missing[:5]}")

    def full(e):
        return f"{e['fieldOne'].strip()} {proper_surname(e['fieldTwo'].strip())}".strip()

    def team_of(e, entrant=None, entry_driver=None):
        # El "equipo" de un privado es su propio nombre (el de la tabla o el de la inscripción): entonces, la marca.
        own = {fold(f"{e['fieldOne']} {e['fieldTwo']}"), fold(entry_driver)}
        for cand in (e.get("fieldFive"), entrant):
            if cand and cand.strip() and cand.strip() != "None" and fold(cand) not in own:
                return proper_team(cand.strip())
        return (e.get("fieldFour") or "").strip()

    refs = {r["championshipEntryId"]: str(ents[r["championshipEntryId"]]["personId"]) for r in rows}
    if len(set(refs.values())) != len(refs):
        raise RuntimeError("personId repetido en la tabla")

    # 5. (Opcional) número y equipo de la inscripción más reciente, del rally objetivo hacia atrás.
    found = {}
    if numbers:
        want = set(refs.values())
        ran_events = [r for r in reversed(events) if r["order"] <= tord and ran(r["eventId"])]
        for r in ran_events:
            if want <= found.keys():
                break
            ev = http.get(f"/events/{r['eventId']}.json")
            main = [x for x in ev.get("rallies", []) if x.get("isMain")]
            if not main:
                continue
            for x in http.get(f"/events/{r['eventId']}/rallies/{main[0]['rallyId']}/entries.json"):
                pid = str((x.get("driver") or {}).get("personId") or x.get("driverId") or "")
                if pid in want and pid not in found:
                    found[pid] = ((x.get("identifier") or "").strip() or None, ((x.get("entrant") or {}).get("name") or "").strip() or None, (x.get("driver") or {}).get("fullName"))
        lost = want - found.keys()
        if lost:
            notes.append(f"sin inscripción hallada (número vacío): {sorted(lost)}")

    drivers, standings = [], []
    for r in rows:
        e = ents[r["championshipEntryId"]]
        ref = refs[r["championshipEntryId"]]
        num, entrant, entry_driver = found.get(ref, (None, None, None))
        drivers.append({"name": full(e), "team": team_of(e, entrant, entry_driver), "numberText": num, "ref": ref})
        standings.append({"pos": r["overallPosition"], "points": r["overallPoints"], "driverRef": ref})
    return {"status": "ready", "throughRound": rnd, "sourceEvent": {"eventId": tev, "order": tord, "name": tname}, "championshipId": cid,
            "seasonId": season_id, "drivers": drivers, "standings": standings, "notes": notes}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--year", type=int, required=True)
    ap.add_argument("--category", choices=sorted(CHAMPIONSHIP), required=True)
    ap.add_argument("--round", type=int, required=True)
    ap.add_argument("--race-day", type=dt.date.fromisoformat, required=True)
    ap.add_argument("--numbers", action="store_true")
    ap.add_argument("--no-live", action="store_true")
    ap.add_argument("--offline")
    ap.add_argument("--save")
    a = ap.parse_args()
    http = Http(a.offline, a.save)
    try:
        out = fetch(http, a.year, a.category, a.round, a.race_day, a.numbers, not a.no_live)
    except NotReady as e:
        out = {"status": "not-ready", "reason": str(e)}
    except Exception as e:  # noqa
        out = {"status": "error", "reason": f"{type(e).__name__}: {e}"}
    out["requests"] = http.logical  # las que haría una corrida sin caché
    out["networkRequests"] = http.requests
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
    print()


if __name__ == "__main__":
    main()
