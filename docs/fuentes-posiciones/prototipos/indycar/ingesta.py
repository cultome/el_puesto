"""
Prototipo de la fuente IndyCar / Indy NXT para El Puesto (SOLO investigación).

Fuente: los JSON públicos /api/results/* que descargan las páginas de Resultados de indycar.com
e indynxt.com (sin llave, sin reto). La página /standings/<año> (SSR) trae lo mismo que
YearPointSummary pero sin ids de carrera: sirve de verificación, no de fuente principal.

Uso: python3 ingesta.py ic|nx <fecha_de_nuestro_calendario> [--offline]
"""
import json, os, sys, time, urllib.request, csv, datetime

UA = 'el-puesto-backend (ingesta de posiciones; https://elpuesto.app)'
SERIES = {  # clave -> (host, GUID de la serie, categoría en data/campeonatos/2026/indycar.csv)
    'ic': ('www.indycar.com', 'b856a4f1-e85c-4fac-8c36-fd58d962227a', 'IndyCar Series'),
    'nx': ('www.indynxt.com', '09341e09-3216-4f89-a45f-db697d72ee13', 'Indy NXT'),
}
HERE = os.path.dirname(os.path.abspath(__file__))
OFFLINE = '--offline' in sys.argv
_last = [0.0]
requests_made = []

def get_json(url, cache):
    path = os.path.join(HERE, cache)
    if os.path.exists(path) and (OFFLINE or 'EventsSessionDetails' in url):
        return json.load(open(path, encoding='utf-8'))
    wait = 1.0 - (time.time() - _last[0])
    if wait > 0: time.sleep(wait)          # cortesía: ~1 petición por segundo
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read()
    _last[0] = time.time()
    requests_made.append(url)
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    open(path, 'wb').write(body)
    return json.loads(body)

class NotReady(Exception): pass

SIMULAR = None  # [ids de carrera que se "borran"] para simular una tabla de media temporada

def simular(yps, borrar):
    import copy
    y = copy.deepcopy(yps)
    for d in y['DriverList']:
        for p in d['Points']:
            if p['EventsSessionsID'] in borrar: p['Points'] = 0
        d['TotalPoints'] = sum(p['Points'] for p in d['Points'] if p['Track'] != 'Total')
        for p in d['Points']:
            if p['Track'] == 'Total': p['Points'] = d['TotalPoints']
    return y
class Ahead(NotReady): pass

def calendar(category):
    rows = [r for r in csv.DictReader(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../../data/campeonatos/2026/indycar.csv'))) if r['categoria'] == category]
    return [(int(r['ronda']), datetime.date.fromisoformat(r['fecha']), r['evento']) for r in rows]

def fetch(key, season, rounds, round_no, race_day):
    host, guid, _ = SERIES[key]
    base = f'https://{host}/api/results'
    yps = get_json(f'{base}/YearPointSummary?year={season}&id={guid}', f'{key}-YearPointSummary-{season}.json')
    if yps.get('Year') != season:
        raise RuntimeError(f"la fuente devolvió el año {yps.get('Year')}, no {season}")
    if SIMULAR is not None:
        yps = simular(yps, SIMULAR)
    cols = [c for c in yps['RaceAbbreviations'] if c['Track'] != 'Total']
    rows = yps['DriverList']
    if not rows:
        raise NotReady(f'{host} aún no publica la tabla de {season}')
    if len(cols) != rounds:
        raise RuntimeError(f'{host} tiene {len(cols)} carreras y nuestro calendario {rounds}: revisar calendario')
    # Puntos por (piloto, carrera): la columna se identifica por EventsSessionsID, nunca por índice/Track.
    col_sum = {c['EventsSessionsID']: 0 for c in cols}
    for d in rows:
        for p in d['Points']:
            if p['EventsSessionsID'] in col_sum:
                col_sum[p['EventsSessionsID']] += p['Points'] or 0
        if sum(p['Points'] or 0 for p in d['Points'] if p['Track'] != 'Total') != d['TotalPoints']:
            raise NotReady(f"tabla inconsistente: {d['DriverName']} no suma su total")
    reflected = [col_sum[c['EventsSessionsID']] > 0 for c in cols]
    k = sum(reflected)
    if reflected != [True] * k + [False] * (len(cols) - k):
        # p. ej. una carrera pospuesta al final de la temporada: hay que revisarlo a mano
        raise RuntimeError(f'las carreras reflejadas no son las primeras {k} del calendario del sitio: {reflected}')
    if k < round_no:
        raise NotReady(f'la tabla refleja {k} carreras; falta la fecha {round_no}')
    if k > round_no:
        raise Ahead(f'la tabla ya refleja {k} carreras (> {round_no}); solo publica la vigente')

    # Carrera objetivo = columna k-1 (orden del sitio: por fecha y luego id). Se coteja su día.
    target = cols[round_no - 1]
    race = get_json(f"{base}/EventsSessionDetails?id={target['EventsSessionsID']}", f"sesiones/{key}-{target['EventsSessionsID']}.json")
    sd = datetime.datetime.strptime(race['SessionDate'], '%m/%d/%Y').date()
    if race.get('SessionType') != 'R' or abs((sd - race_day).days) > 1:
        raise RuntimeError(f"la columna {round_no} del sitio es «{race['EventName']}» del {sd}; nuestra fecha {round_no} es el {race_day}")
    # Verificación extra: puntos de la columna == PointsEarned del resultado de esa carrera.
    by_name_col = {d['DriverName']: next((p['Points'] for p in d['Points'] if p['EventsSessionsID'] == target['EventsSessionsID']), 0) for d in rows}
    recs = [r for r in race['records'] if not r.get('IsDeleted')]
    diff = [(r['DriverName'], r['PointsEarned'], by_name_col.get(r['DriverName'])) for r in recs if by_name_col.get(r['DriverName']) != r['PointsEarned']]
    if diff:
        raise NotReady(f'la tabla aún no coincide con el resultado de «{race["EventName"]}»: {diff[:3]}')

    # ids: DriversByYear trae DriverOverrideID con la MISMA OverallPosition (única).
    dby = get_json(f'{base}/DriversByYear?year={season}&id={guid}', f'{key}-DriversByYear-{season}.json')
    id_by_pos = {d['OverallPosition']: d for d in dby}
    ids = {}
    for d in rows:
        b = id_by_pos.get(d['OverallPosition'])
        if not b or f"{b['FirstName']} {b['LastName']}".split() != d['DriverName'].split():
            raise NotReady(f"DriversByYear no coincide con la tabla en la posición {d['OverallPosition']}")
        ids[d['OverallPosition']] = b['DriverOverrideID']

    # Número y equipo: su aparición MÁS RECIENTE, de la carrera objetivo hacia atrás.
    found = {}
    for i in range(round_no - 1, -1, -1):
        if set(ids.values()) <= set(found): break
        sid = cols[i]['EventsSessionsID']
        rr = race if i == round_no - 1 else get_json(f'{base}/EventsSessionDetails?id={sid}', f'sesiones/{key}-{sid}.json')
        for r in rr['records']:
            if r.get('IsDeleted'): continue
            ref = r['DriverOverrideID']
            if ref in ids.values() and ref not in found:
                found[ref] = ((r.get('CarNumber') or '').strip() or None, (r.get('TeamName') or '').strip(), rr['EventName'], r.get('DriverUrl'))
    drivers, standings = [], []
    for d in sorted(rows, key=lambda d: d['OverallPosition']):
        ref = ids[d['OverallPosition']]
        num, team, seen_at, url = found.get(ref, (None, '', None, None))
        drivers.append(dict(name=' '.join(d['DriverName'].split()), team=team, numberText=num, ref=str(ref), _seen=seen_at, _url=url))
        standings.append(dict(pos=d['OverallPosition'], points=d['TotalPoints'], driverRef=str(ref)))
    return drivers, standings

if __name__ == '__main__':
    key, day = sys.argv[1], datetime.date.fromisoformat(sys.argv[2])
    cal = calendar(SERIES[key][2])
    rnd = max(r for r, f, _ in cal if f <= day)
    race_day = next(f for r, f, _ in cal if r == rnd)
    try:
        drivers, standings = fetch(key, 2026, len(cal), rnd, race_day)
        print(f'Ready: fecha {rnd} ({race_day})')
        for s, d in zip(standings, drivers):
            print(f"{s['pos']:>2} #{d['numberText'] or '?':>3} {d['name']:<24} {d['team']:<36} {s['points']:>4} ref={d['ref']}  (visto en {d['_seen']})")
    except NotReady as e:
        print('NotReady:', e)
    print('peticiones:', len(requests_made))
    for u in requests_made: print('  ', u)
