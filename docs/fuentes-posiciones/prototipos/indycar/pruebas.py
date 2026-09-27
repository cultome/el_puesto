"""Escenarios de 'fecha reflejada' simulados sobre la tabla final (sin red: usa las muestras)."""
import datetime, json, ingesta
ingesta.OFFLINE = True
D = datetime.date
ic = [c['EventsSessionsID'] for c in json.load(open('ic-YearPointSummary-2026.json'))['RaceAbbreviations'] if c['Track'] != 'Total']
def run(nombre, borrar, rnd, dia):
    ingesta.SIMULAR = borrar
    try:
        d, s = ingesta.fetch('ic', 2026, 18, rnd, dia); r = f'Ready(fecha {rnd})'
    except ingesta.Ahead as e: r = f'Ahead: {e}'
    except ingesta.NotReady as e: r = f'NotReady: {e}'
    except Exception as e: r = f'ERROR: {e}'
    print(f'{nombre:<60} -> {r}')
run('tras la fecha 10, se pide la 10', ic[10:], 10, D(2026,6,21))
run('tras la fecha 10, se pide la 9', ic[10:], 9, D(2026,6,7))
run('tras la fecha 10, se pide la 11', ic[10:], 11, D(2026,7,5))
run('Milwaukee: solo corrió la «carrera 2» (6738), se pide la 16', ic[16:], 16, D(2026,8,29))
run('Milwaukee: ya las dos, se pide la 17', ic[17:], 17, D(2026,8,30))
run('carrera pospuesta (hueco en la 12), se pide la 13', [ic[11]] + ic[13:], 13, D(2026,8,9))
run('calendario desfasado (se pide la 10 con otro día)', ic[10:], 10, D(2026,6,28))
