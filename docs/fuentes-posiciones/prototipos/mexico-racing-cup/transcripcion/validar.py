"""Convierte transcripciones .txt (una fila por línea, campos con '|') a JSON y valida.

Formato de cada .txt:
  # meta: clave=valor (categoria, imagen, fecha_impresa, columnas, …)
  pos|piloto|auto|extra|reales|diferencia|c1,c2,…(vacío = no corrió)|suma|peor
Validaciones (las mismas que haría el backend):
  V1 suma de columnas == SUMA            V2 SUMA - PEOR == REALES
  V3 DIFERENCIA == REALES - REALES del líder
  V4 posiciones 1..N contiguas           V5 orden no creciente por REALES
  V6 PEOR == peor fecha (fechas con varias carreras se suman; ausente = 0)
"""
import json, sys, re

def num(s):
    s = s.strip().rstrip('*')
    if s == '': return None
    return float(s.replace(',', '.')) if (',' in s or '.' in s) else int(s)

def load(path):
    meta, rows = {}, []
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line.strip(): continue
        if line.startswith('#'):
            m = re.match(r'#\s*(\w+)=(.*)', line)
            if m: meta[m.group(1)] = m.group(2).strip()
            continue
        f = line.split('|')
        pos, name, auto, extra, reales, dif, cols, suma, peor = (f + [''] * 9)[:9]
        rows.append(dict(pos=int(pos), piloto=name.strip(), auto=auto.strip() or None,
                         extra=extra.strip() or None, puntos_reales=num(reales),
                         diferencia=num(dif), por_carrera=[num(c) for c in cols.split(',')] if cols else [],
                         sancionadas=[i for i, c in enumerate(cols.split(',')) if c.strip().endswith('*')] if cols else [],
                         suma=num(suma), peor_fecha=num(peor)))
    return meta, rows

def validate(meta, rows):
    probs = []
    cols = meta.get('columnas', '').split(',')
    grupos = {}  # fecha -> índices de columnas
    for i, c in enumerate(cols):
        fecha = re.match(r'(\d+)', c).group(1) if c else str(i)
        grupos.setdefault(fecha, []).append(i)
    leader = rows[0]['puntos_reales'] if rows else 0
    for i, r in enumerate(rows):
        pc = r['por_carrera']
        if pc and len(pc) != len(cols):
            probs.append(f"pos {r['pos']} {r['piloto']}: {len(pc)} columnas, se esperaban {len(cols)}")
        s = sum(x for x in pc if x is not None)
        if r['suma'] is not None and pc and abs(s - r['suma']) > 0.01:
            probs.append(f"V1 pos {r['pos']} {r['piloto']}: suma de columnas {s} != SUMA {r['suma']}")
        if r['suma'] is not None and r['puntos_reales'] is not None and (r['peor_fecha'] is not None or meta.get('peor','').startswith('no')):
            if abs(r['suma'] - (r['peor_fecha'] or 0) - r['puntos_reales']) > 0.01:
                probs.append(f"V2 pos {r['pos']} {r['piloto']}: SUMA {r['suma']} - PEOR {r['peor_fecha']} != REALES {r['puntos_reales']}")
        if r['diferencia'] is not None and abs((r['puntos_reales'] - leader) - r['diferencia']) > 0.01:
            probs.append(f"V3 pos {r['pos']} {r['piloto']}: DIF {r['diferencia']} != {r['puntos_reales'] - leader}")
        if r['pos'] != i + 1:
            probs.append(f"V4 fila {i+1}: pos impresa {r['pos']}")
        if i > 0 and r['puntos_reales'] > rows[i-1]['puntos_reales']:
            probs.append(f"V5 pos {r['pos']} {r['piloto']} ({r['puntos_reales']}) tiene más puntos que pos {rows[i-1]['pos']} ({rows[i-1]['puntos_reales']})")
        if r['peor_fecha'] is not None and pc and not meta.get('peor','').startswith('no'):
            # fechas disputadas = las que tienen al menos un dato en la tabla
            jugadas = [f for f, idx in grupos.items() if any(rr['por_carrera'][j] is not None for rr in rows if rr['por_carrera'] for j in idx)]
            por_fecha = {f: sum((pc[j] or 0) for j in grupos[f]) for f in jugadas}
            # regla 9.6: de la primera a la PENÚLTIMA fecha disputada
            candidatas = jugadas[:-1] if meta.get('peor','').startswith('penultima') else jugadas
            # regla 9.6: una fecha con descalificación/sanción (celda amarilla, marcada con *) no cuenta como peor
            candidatas = [f for f in candidatas if not any(j in r['sancionadas'] for j in grupos[f])]
            esperado = min(por_fecha[f] for f in candidatas) if candidatas else 0
            if abs(esperado - r['peor_fecha']) > 0.01:
                probs.append(f"V6 pos {r['pos']} {r['piloto']}: PEOR impresa {r['peor_fecha']}, peor fecha calculada {esperado}")
    return probs

if __name__ == '__main__':
    for path in sys.argv[1:]:
        meta, rows = load(path)
        probs = validate(meta, rows)
        out = path.rsplit('.', 1)[0] + '.json'
        json.dump(dict(meta=meta, filas=rows, problemas=probs), open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f"== {path}: {len(rows)} filas, {len(probs)} problemas -> {out}")
        for p in probs: print('   ', p)
