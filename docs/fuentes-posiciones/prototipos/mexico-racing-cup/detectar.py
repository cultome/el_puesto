"""Prototipo de la DETECCIÓN automática: baja las 8 páginas de puntuación de la MRC y saca la(s)
imagen(es) de la tabla (las de innerpicstmp que no son el encabezado 1100x180 ni el banner 835x136
del pie). El nombre lleva la hora de subida en ms (tinyimg<epoch_ms><azar>.jpg): si cambia, hay tabla nueva.
~1 petición por segundo, User-Agent propio."""
import re, time, urllib.request, datetime, json
UA = 'el-puesto-backend (ingesta de posiciones; https://elpuesto.app)'
PAGINAS = {
    'Copa TC2000': '33-copa-tc2000', 'Súper Turismos': '33-super-turismos-1', 'Copa 1.8': '33-copa-18',
    'Súper Turismos Light 1': '33-st-light-1', 'Súper Turismos Light 2': '33-st-light-2',
    'TCR México': '33-tcr-mxico', 'F4 NACAM': '33-f4-nacam', 'Endurance Challenge': '33-endurance-challenge',
}
IMG = re.compile(r'<img src="(https://duncanwebmin\.notiauto\.com/repository/sitios/innerpicstmp/tinyimg(\d{13})\d*\.jpg)\?nc=\d+"[^>]*width="(\d+)" height="(\d+)"')
out = {}
for cat, slug in PAGINAS.items():
    req = urllib.request.Request(f'https://mexicoracingcup.com/{slug}', headers={'User-Agent': UA})
    html = urllib.request.urlopen(req, timeout=30).read().decode('utf-8')
    tablas = []
    for url, ms, w, h in IMG.findall(html):
        if (w, h) in (('1100', '180'), ('835', '136')):  # encabezado de la categoría / banner del pie
            continue
        subida = datetime.datetime.fromtimestamp(int(ms) / 1000, datetime.timezone(datetime.timedelta(hours=-6)))
        tablas.append(dict(url=url, ancho=int(w), alto=int(h), subida_cdmx=subida.isoformat(timespec='minutes')))
    out[cat] = dict(pagina=f'https://mexicoracingcup.com/{slug}', tablas=tablas)
    time.sleep(1)
print(json.dumps(out, ensure_ascii=False, indent=1))
