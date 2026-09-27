#!/usr/bin/env python3
"""
Carga los datos reales versionados en `data/` al sistema por la API admin.

Idempotente: identifica cada registro por su llave natural (circuito = nombre;
campeonato = nombre (la serie, sin año); temporada = etiqueta dentro del campeonato
("2026", "2025-26"); categoría = nombre dentro de la temporada; oficial =
OMDAI ID; cuenta = correo), así que se puede correr cuantas veces se quiera: crea lo que
falta, actualiza lo que cambió y deja igual lo demás. El calendario de cada categoría se
REEMPLAZA completo con lo que diga el CSV (el CSV es la fuente de verdad).

Solo usa la biblioteca estándar de Python.

    fish -c 'source ./setenv; python3 scripts/cargar-datos.py --dry-run'
    fish -c 'source ./setenv; python3 scripts/cargar-datos.py'

Credenciales: --api / --key, o EL_PUESTO_API / EL_PUESTO_ADMIN_KEY del entorno.
"""
import argparse
import csv
import datetime
import re
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent / "data"
# Posiciones de puestos/activos: versionadas en circuitos/posiciones/ o, si vienen de un plano
# que no es público (p. ej. el "Marshal Posts" de OMDAI), en privado/circuitos/posiciones/.
DIRS_POSICIONES = (RAIZ / "circuitos" / "posiciones", RAIZ / "privado" / "circuitos" / "posiciones")
# Mapeo de roles del "Track Personnel" de OMDAI → roles de la app (acompaña al roster privado).
MAPEO_ROLES = RAIZ / "privado" / "eventos" / "roles-omdai.csv"
AREAS = {"INTERVENCION", "COMUNICACION", "RECOVERY", "ESCRUTINIO", "MEDICO"}
ESTATUS = {"INVITED", "PENDING_APPROVAL", "ACTIVE", "SUSPENDED"}


class Error(Exception):
    pass


# ——— Lectura y validación local de los archivos ———

def leer_csv(ruta):
    with open(ruta, newline="", encoding="utf-8") as f:
        return [(n, fila) for n, fila in enumerate(csv.DictReader(f), start=2)]


def fecha(valor, donde):
    try:
        return datetime.date.fromisoformat(valor)
    except ValueError:
        raise Error(f"{donde}: fecha inválida '{valor}' (usa AAAA-MM-DD)")


def leer_circuitos():
    ruta = RAIZ / "circuitos" / "circuitos.csv"
    circuitos, nombres = {}, set()
    for n, f in leer_csv(ruta):
        donde = f"{ruta.relative_to(RAIZ.parent)}:{n}"
        clave, nombre = f["clave"].strip(), f["nombre"].strip()
        if not clave or not nombre or not f["ubicacion"].strip():
            raise Error(f"{donde}: clave, nombre y ubicacion son obligatorios")
        if clave in circuitos:
            raise Error(f"{donde}: clave repetida '{clave}'")
        if nombre in nombres:
            raise Error(f"{donde}: nombre repetido '{nombre}' (el nombre es la llave en el sistema)")
        nombres.add(nombre)
        circuitos[clave] = {"name": nombre, "location": f["ubicacion"].strip(), "country": f["pais"].strip() or None}
    return circuitos


def leer_trazados(circuitos):
    """[{circuito, name, lengthM, curves, direction}] de circuitos/trazados.csv."""
    ruta = RAIZ / "circuitos" / "trazados.csv"
    if not ruta.exists():
        return []
    trazados, vistos = [], set()
    for n, f in leer_csv(ruta):
        donde = f"{ruta.relative_to(RAIZ.parent)}:{n}"
        clave, nombre = f["circuito"].strip(), f["nombre"].strip()
        if clave not in circuitos:
            raise Error(f"{donde}: circuito '{clave}' no existe en circuitos.csv")
        if (clave, nombre) in vistos:
            raise Error(f"{donde}: trazado repetido '{nombre}' en {clave}")
        vistos.add((clave, nombre))
        if f["sentido"].strip() not in ("Horario", "Antihorario"):
            raise Error(f"{donde}: sentido debe ser Horario o Antihorario")
        try:
            largo, curvas = int(f["longitud_m"]), int(f["curvas"])
        except ValueError:
            raise Error(f"{donde}: longitud_m y curvas deben ser enteros")
        trazados.append({"circuito": clave, "name": nombre, "lengthM": largo, "curves": curvas, "direction": f["sentido"].strip()})
    return trazados


TIPOS_ACTIVO = {"HIAB", "AMBULANCIA", "IFRT", "TELEHANDLER", "TRACK_SWEEPER", "SAFETY_CAR", "DRIVER_RIDER"}


def slug(texto):
    import unicodedata
    t = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode().lower()
    return "-".join("".join(c if c.isalnum() else " " for c in t).split())


def leer_dibujos(circuitos, trazados):
    """{(circuito, trazado): {"geo": [{lat, lon}], "fuente"}} de
    circuitos/dibujos/<circuito>/<slug-trazado>.geojson (un Feature LineString, [lon, lat]
    como manda GeoJSON; el cierre es implícito: sin repetir el primer vértice)."""
    base = RAIZ / "circuitos" / "dibujos"
    nombres = {(t["circuito"], slug(t["name"])): t["name"] for t in trazados}
    salida = {}
    for ruta in sorted(base.glob("*/*.geojson")) if base.exists() else []:
        donde = ruta.relative_to(RAIZ.parent)
        clave, trazado = ruta.parent.name, nombres.get((ruta.parent.name, ruta.stem))
        if clave not in circuitos or trazado is None:
            raise Error(f"{donde}: no corresponde a un trazado de trazados.csv (carpeta = circuito, archivo = nombre del trazado en minúsculas con guiones)")
        try:
            f = json.loads(ruta.read_text(encoding="utf-8"))
        except ValueError as e:
            raise Error(f"{donde}: GeoJSON inválido ({e})")
        geom, props = f.get("geometry") or {}, f.get("properties") or {}
        if f.get("type") != "Feature" or geom.get("type") != "LineString":
            raise Error(f"{donde}: debe ser un Feature con geometría LineString")
        if not str(props.get("fuente", "")).strip():
            raise Error(f"{donde}: falta properties.fuente")
        pts = geom.get("coordinates") or []
        if len(pts) >= 2 and pts[0] == pts[-1]:
            pts = pts[:-1]
        if len(pts) < 3 or any(len(p) < 2 or not (-180 <= p[0] <= 180 and -90 <= p[1] <= 90) for p in pts):
            raise Error(f"{donde}: se necesitan ≥ 3 vértices [lon, lat] válidos")
        salida[(clave, trazado)] = {"geo": [{"lat": p[1], "lon": p[0]} for p in pts], "fuente": props["fuente"]}
    return salida


def leer_logos(carpeta, columna, conocidos, donde_existen):
    """{llave: ruta} de <carpeta>/logos.csv (archivo PNG/JPEG en <carpeta>/logos/). Conviene
    cuadrado: el sistema recorta al centro. Sirve para circuitos (llave = clave) y
    campeonatos (llave = nombre del campeonato)."""
    ruta = RAIZ / carpeta / "logos.csv"
    if not ruta.exists():
        return {}
    logos = {}
    for n, f in leer_csv(ruta):
        donde = f"{ruta.relative_to(RAIZ.parent)}:{n}"
        clave = f[columna].strip()
        if clave not in conocidos:
            raise Error(f"{donde}: {columna} '{clave}' no existe en {donde_existen}")
        if clave in logos:
            raise Error(f"{donde}: logo repetido para '{clave}'")
        archivo = RAIZ / carpeta / "logos" / f["archivo"].strip()
        if not archivo.is_file():
            raise Error(f"{donde}: no existe {archivo.relative_to(RAIZ.parent)}")
        cabeza = archivo.read_bytes()[:8]
        if not (cabeza.startswith(b"\x89PNG") or cabeza.startswith(b"\xff\xd8")):
            raise Error(f"{donde}: {archivo.name} debe ser PNG o JPEG")
        if not f["fuente"].strip() or not f["licencia"].strip():
            raise Error(f"{donde}: fuente y licencia son obligatorias")
        logos[clave] = archivo
    return logos


def archivos_posiciones():
    return sorted(r for base in DIRS_POSICIONES if base.exists() for r in base.glob("*/*.csv"))


def leer_posiciones(circuitos, trazados):
    """{(circuito, trazado): [filas]} de <DIRS_POSICIONES>/<circuito>/<slug-trazado>.csv."""
    salida = {}
    nombres = {(t["circuito"], slug(t["name"])): t["name"] for t in trazados}
    for ruta in archivos_posiciones():
        clave, trazado = ruta.parent.name, nombres.get((ruta.parent.name, ruta.stem))
        donde0 = ruta.relative_to(RAIZ.parent)
        if clave not in circuitos or trazado is None:
            raise Error(f"{donde0}: no corresponde a un trazado de trazados.csv (carpeta = circuito, archivo = nombre del trazado en minúsculas con guiones)")
        if (clave, trazado) in salida:
            raise Error(f"{donde0}: el trazado ya tiene posiciones en otra carpeta (versionada o privada: solo una)")
        filas, etiquetas = [], set()
        for n, f in leer_csv(ruta):
            donde = f"{donde0}:{n}"
            tipo, etq, en_mapa = f["tipo"].strip(), f["etiqueta"].strip(), f["en_mapa"].strip().lower() != "no"
            if tipo != "PUESTO" and tipo not in TIPOS_ACTIVO:
                raise Error(f"{donde}: tipo '{tipo}' no válido (PUESTO o {', '.join(sorted(TIPOS_ACTIVO))})")
            if not etq or etq in etiquetas:
                raise Error(f"{donde}: etiqueta vacía o repetida '{etq}'")
            etiquetas.add(etq)
            lat = float(f["lat"]) if f["lat"].strip() else None
            lon = float(f["lon"]) if f["lon"].strip() else None
            if en_mapa and (lat is None or lon is None):
                raise Error(f"{donde}: '{etq}' está en el mapa pero no tiene lat/lon")
            fila = {"tipo": tipo, "label": etq, "lat": lat, "lon": lon, "onMap": en_mapa}
            if tipo == "PUESTO":
                try:
                    fila["number"] = int(f["numero"])
                except ValueError:
                    raise Error(f"{donde}: los puestos necesitan numero entero >= 1")
            filas.append(fila)
        salida[(clave, trazado)] = filas
    return salida


def leer_calendarios(circuitos):
    """{(campeonato, temporada): {categoria: [fechas]}} en orden de aparición.

    `campeonato` es la serie sin año ("Fórmula E") y `temporada` su etiqueta ("2026", o
    "2025-26" si cruza el año)."""
    campeonatos = {}
    archivos = sorted(p for p in (RAIZ / "campeonatos").rglob("*.csv")
                      if not p.name.startswith("_") and p.name not in (FUENTES_POSICIONES, "logos.csv"))
    for ruta in archivos:
        for n, f in leer_csv(ruta):
            donde = f"{ruta.relative_to(RAIZ.parent)}:{n}"
            temporada = f["temporada"].strip()
            if not re.fullmatch(r"\d{4}(-\d{2})?", temporada):
                raise Error(f"{donde}: temporada '{temporada}' debe ser '2026' o '2025-26'")
            if re.search(r"\s\d{4}(-\d{2})?$", f["campeonato"].strip()):
                raise Error(f"{donde}: el campeonato va sin año ('{f['campeonato']}'); el año va en temporada")
            try:
                ronda = int(f["ronda"])
            except ValueError:
                raise Error(f"{donde}: ronda debe ser entero")
            dia = fecha(f["fecha"], donde)
            inicio = fecha(f["fecha_inicio"], donde) if f["fecha_inicio"].strip() else None
            if inicio and inicio > dia:
                raise Error(f"{donde}: fecha_inicio posterior a fecha")
            clave, sede = f["circuito"].strip(), f["sede"].strip()
            if clave and clave not in circuitos:
                raise Error(f"{donde}: circuito '{clave}' no existe en circuitos.csv")
            if not clave and not sede:
                raise Error(f"{donde}: sin circuito hace falta sede ('Ciudad, País')")
            if not f["evento"].strip() or not f["fuente"].strip():
                raise Error(f"{donde}: evento y fuente son obligatorios")
            cats = campeonatos.setdefault((f["campeonato"].strip(), temporada), {})
            fechas = cats.setdefault(f["categoria"].strip(), [])
            if any(x["number"] == ronda for x in fechas):
                raise Error(f"{donde}: ronda {ronda} repetida en {f['categoria']}")
            fila = {"number": ronda, "date": dia.isoformat(), "name": f["evento"].strip(), "_circuito": clave or None}
            if inicio:
                fila["startDate"] = inicio.isoformat()
            if not clave:
                fila["location"] = sede
            fechas.append(fila)
    for cats in campeonatos.values():
        for fechas in cats.values():
            fechas.sort(key=lambda x: x["number"])
    return campeonatos


FUENTES_POSICIONES = "fuentes-posiciones.csv"


def leer_lecturas(campeonatos):
    """Tablas de posiciones LEÍDAS de imágenes (campeonatos que solo publican así su tabla, p. ej.
    la México Racing Cup): campeonatos/<año>/posiciones-imagen/*.json, una por categoría."""
    lecturas = []
    for ruta in sorted((RAIZ / "campeonatos").glob("*/posiciones-imagen/*.json")):
        donde = ruta.relative_to(RAIZ.parent)
        try:
            d = json.loads(ruta.read_text())
        except json.JSONDecodeError as e:
            raise Error(f"{donde}: JSON inválido ({e})")
        llave = (d.get("campeonato", ""), d.get("temporada", ""))
        cat = d.get("categoria", "")
        if cat not in campeonatos.get(llave, {}):
            raise Error(f"{donde}: la categoría '{cat}' de {llave[0]} {llave[1]} no está en los calendarios")
        filas = d.get("rows") or []
        if not d.get("imageUrl") or not isinstance(d.get("throughRound"), int) or not filas:
            raise Error(f"{donde}: faltan imageUrl, throughRound o rows")
        pos = [f.get("pos") for f in filas]
        if len(set(pos)) != len(pos):
            raise Error(f"{donde}: posiciones repetidas")
        if d["throughRound"] > len(campeonatos[llave][cat]):
            raise Error(f"{donde}: throughRound {d['throughRound']} fuera del calendario")
        lecturas.append({"campeonato": llave[0], "temporada": llave[1], "categoria": cat,
                         "cuerpo": {"imageUrl": d["imageUrl"], "throughRound": d["throughRound"],
                                    "rows": [{k: f.get(k) for k in ("pos", "name", "team", "points")} for f in filas]}})
    return lecturas


def cargar_lecturas(api, lecturas, contar):
    """Sube cada tabla leída si cambió (imagen, fecha o filas); el backend la publica en cuanto la
    categoría tiene la fuente mexicoracingcup-img (por eso va después de las fuentes)."""
    if not lecturas:
        return
    temporadas = {(c["name"], c["seasonLabel"]): c["id"] for c in api.call("GET", "/championships")}
    for l in lecturas:
        champ_id = temporadas.get((l["campeonato"], l["temporada"]))
        cats = {c["name"]: c["id"] for c in api.call("GET", f"/championships/{champ_id}/categories")} if champ_id else {}
        cat_id = cats.get(l["categoria"])
        if not cat_id:
            contar("creados")  # dry-run: la categoría aún no existe
            continue
        try:
            actual = api.call("GET", f"/categories/{cat_id}/standings-reading")
        except Error as e:
            if "404" not in str(e):
                raise
            actual = None
        if actual and all(actual.get(k) == l["cuerpo"][k] for k in ("imageUrl", "throughRound")) and \
                [{k: f.get(k) for k in ("pos", "name", "team", "points")} for f in actual.get("rows", [])] == \
                [{**f, "points": float(f["points"])} for f in l["cuerpo"]["rows"]]:
            contar("sin cambio")
            continue
        r = api.call("PUT", f"/categories/{cat_id}/standings-reading", l["cuerpo"], escribe=True)
        run = (r or {}).get("run") or {}
        print(f"  lectura {l['categoria']}: {len(l['cuerpo']['rows'])} filas hasta la fecha {l['cuerpo']['throughRound']}"
              + (f" → {run.get('status')} ({run.get('detail')})" if run else ""))
        contar("actualizados" if actual else "creados")
    print(f"tablas leídas de imágenes: {len(lecturas)} categorías")


def leer_fuentes_posiciones(campeonatos):
    """Posiciones automáticas: [{campeonato, temporada, categoria, source, param}]. La
    categoría debe existir en los calendarios; la fuente la valida la API al cargar."""
    ruta = RAIZ / "campeonatos" / FUENTES_POSICIONES
    if not ruta.exists():
        return []
    filas = []
    for n, f in leer_csv(ruta):
        donde = f"{ruta.relative_to(RAIZ.parent)}:{n}"
        llave = (f["campeonato"].strip(), f["temporada"].strip())
        cat = f["categoria"].strip()
        if cat not in campeonatos.get(llave, {}):
            raise Error(f"{donde}: la categoría '{cat}' de {llave[0]} {llave[1]} no está en los calendarios")
        if not f["fuente"].strip():
            raise Error(f"{donde}: falta la fuente")
        filas.append({"campeonato": llave[0], "temporada": llave[1], "categoria": cat,
                      "source": f["fuente"].strip(), "param": f["parametro"].strip() or None})
    return filas


def cargar_fuentes_posiciones(api, fuentes, contar):
    """Activa la ingesta automática de posiciones de cada categoría listada (idempotente:
    solo escribe si la fuente/parámetro cambió; no apaga las que no estén en el archivo)."""
    if not fuentes:
        return
    estado = {s["categoryId"]: s for s in api.call("GET", "/standings-ingest")}
    temporadas = {(c["name"], c["seasonLabel"]): c["id"] for c in api.call("GET", "/championships")}
    for f in fuentes:
        champ_id = temporadas.get((f["campeonato"], f["temporada"]))
        cats = {c["name"]: c["id"] for c in api.call("GET", f"/championships/{champ_id}/categories")} if champ_id else {}
        cat_id = cats.get(f["categoria"])
        if not cat_id:
            contar("creados")  # dry-run: la categoría aún no existe
            continue
        actual = estado.get(cat_id)
        if actual and actual["source"] == f["source"] and actual.get("param") == f["param"] and actual["enabled"]:
            contar("sin cambio")
            continue
        api.call("PUT", f"/categories/{cat_id}/standings-ingest", {"source": f["source"], "param": f["param"], "enabled": True}, escribe=True)
        contar("actualizados" if actual else "creados")
    print(f"posiciones automáticas: {len(fuentes)} categorías")


def leer_eventos(circuitos, trazados, campeonatos):
    """Eventos (versionados) + su roster privado si existe + mapeo de roles OMDAI → app."""
    ruta = RAIZ / "eventos" / "eventos.csv"
    if not ruta.exists():
        return [], {}
    mapeo = {}
    if MAPEO_ROLES.exists():
        for n, f in leer_csv(MAPEO_ROLES):
            mapeo[(f["posicion"].strip(), f["rol_origen"].strip())] = f["rol"].strip()
    eventos = []
    for n, f in leer_csv(ruta):
        donde = f"{ruta.relative_to(RAIZ.parent)}:{n}"
        clave = f["circuito"].strip()
        if clave not in circuitos:
            raise Error(f"{donde}: circuito '{clave}' no existe")
        tz = [t.strip() for t in f["trazados"].split("|") if t.strip()]
        faltan = [t for t in tz if not any(x["circuito"] == clave and x["name"] == t for x in trazados)]
        if not tz or faltan:
            raise Error(f"{donde}: trazados {faltan or '(vacío)'} no están en trazados.csv para {clave}")
        champs = []
        for c in [c.strip() for c in f["campeonatos"].split("|") if c.strip()]:
            nombre, _, temp = c.rpartition(":")
            if (nombre, temp) not in campeonatos:
                raise Error(f"{donde}: campeonato '{c}' no está en los calendarios")
            champs.append((nombre, temp))
        ini, fin = fecha(f["inicio"], donde), fecha(f["fin"], donde)
        if ini > fin:
            raise Error(f"{donde}: inicio posterior a fin")
        # Autoregistro por honor: permitido salvo "no" (los eventos con roster completo).
        auto = (f.get("autoregistro") or "").strip().lower()
        if auto not in ("", "si", "sí", "no"):
            raise Error(f"{donde}: autoregistro '{auto}' inválido (si | no; vacío = si)")
        ev = {"name": f["nombre"].strip(), "startsOn": ini.isoformat(), "endsOn": fin.isoformat(),
              "selfRegistration": auto != "no",
              "_circuito": clave, "_trazados": tz, "_campeonatos": champs, "_roster": []}
        rr = f.get("roster", "").strip()
        if rr and (RAIZ / "privado" / rr).exists():
            for m, r in leer_csv(RAIZ / "privado" / rr):
                d2 = f"data/privado/{rr}:{m}"
                pos, rol_o = r["posicion"].strip(), r["rol_origen"].strip()
                ev["_roster"].append({"posicion": pos, "rol_origen": rol_o, "omdai": int(r["omdai_id"]),
                                      "nombre": r["nombre"].strip() or f"Oficial {r['omdai_id']}", "_donde": d2})
        eventos.append(ev)
    return eventos, mapeo


def leer_privado():
    ruta = RAIZ / "privado" / "cuentas.csv"
    if not ruta.exists():
        return []
    cuentas = []
    for n, f in leer_csv(ruta):
        donde = f"data/privado/cuentas.csv:{n}"
        correo = f["correo"].strip().lower()
        if "@" not in correo:
            raise Error(f"{donde}: correo inválido")
        area = f["area"].strip().upper()
        if area and area not in AREAS:
            raise Error(f"{donde}: area '{area}' no válida ({', '.join(sorted(AREAS))})")
        estatus = (f["estatus"].strip() or "ACTIVE").upper()
        if estatus not in ESTATUS:
            raise Error(f"{donde}: estatus '{estatus}' no válido")
        try:
            omdai = int(f["omdai_id"])
        except ValueError:
            raise Error(f"{donde}: omdai_id debe ser entero")
        cuentas.append({
            "correo": correo, "omdai": omdai, "nombre": f["nombre"].strip(), "area": area or None,
            "estatus": estatus, "activo_desde": int(f["activo_desde"]) if f.get("activo_desde", "").strip() else None,
        })
    return cuentas


# ——— API admin ———

class Api:
    def __init__(self, base, key, dry):
        self.base, self.key, self.dry = base.rstrip("/"), key, dry

    def call(self, metodo, ruta, cuerpo=None, escribe=False):
        url = f"{self.base}/admin{ruta}"
        if escribe and self.dry:
            url += ("&" if "?" in url else "?") + "dryRun=true"
        datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
        req = urllib.request.Request(url, data=datos, method=metodo, headers={
            "X-Admin-Key": self.key, "Accept": "application/json", "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                texto = r.read().decode()
        except urllib.error.HTTPError as e:
            raise Error(f"{metodo} {ruta} → {e.code}: {e.read().decode()[:400]}")
        except urllib.error.URLError as e:
            raise Error(f"no se pudo conectar a {self.base}: {e.reason}")
        return json.loads(texto) if texto.strip() else None

    def subir(self, ruta, datos, tipo):
        """POST de bytes crudos (imágenes). En dry-run no se sube (el endpoint no valida)."""
        if self.dry:
            return
        req = urllib.request.Request(f"{self.base}/admin{ruta}", data=datos, method="POST",
                                     headers={"X-Admin-Key": self.key, "Content-Type": tipo})
        try:
            urllib.request.urlopen(req, timeout=60).read()
        except urllib.error.HTTPError as e:
            raise Error(f"POST {ruta} → {e.code}: {e.read().decode()[:400]}")

    def existe(self, ruta):
        req = urllib.request.Request(f"{self.base}/admin{ruta}", method="GET", headers={"X-Admin-Key": self.key})
        try:
            urllib.request.urlopen(req, timeout=30).read()
            return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            raise Error(f"GET {ruta} → {e.code}")


def id_trazado(api, circuit_id, nombre):
    if str(circuit_id).startswith("(nuevo"):
        return None
    return next((t["id"] for t in api.call("GET", f"/circuits/{circuit_id}/trazados") if t["name"] == nombre), None)


def cargar_posiciones(api, ids_circuito, posiciones, contar, archivar_sobrantes=False):
    """Puestos y activos por trazado, conservando el id de cada etiqueta existente (las
    asignaciones apuntan a ese id); lo que ya no esté en el CSV se archiva.

    OJO: el sistema también crea puestos por su cuenta (los que proponen los oficiales y
    aprueba el admin). Si hay puestos vivos que el CSV no conoce, NO se archivan en
    silencio: se pide exportarlos primero (--exportar-posiciones) o confirmarlo con
    --archivar-sobrantes."""
    for (clave, trazado), filas in posiciones.items():
        tz = id_trazado(api, ids_circuito[clave], trazado)
        if tz is None:
            print(f"  posiciones {clave}/{trazado}: el trazado aún no existe (se cargan en la siguiente corrida)")
            continue
        p_ids = {p["label"]: p["id"] for p in api.call("GET", f"/trazados/{tz}/puestos")}
        del_csv = {f["label"] for f in filas if f["tipo"] == "PUESTO"}
        sobrantes = sorted(set(p_ids) - del_csv)
        if sobrantes and not archivar_sobrantes:
            raise Error(
                f"posiciones {clave}/{trazado}: el sistema tiene puestos que el CSV no conoce "
                f"({', '.join(sobrantes[:8])}{'…' if len(sobrantes) > 8 else ''}; p. ej. aprobados desde las "
                f"propuestas de los oficiales). Tráelos al CSV con --exportar-posiciones y haz commit, o "
                f"confirma que se archiven con --archivar-sobrantes")
        a_ids = {a["label"]: a["id"] for a in api.call("GET", f"/trazados/{tz}/assets")}
        puestos = [{k: v for k, v in {"id": p_ids.get(f["label"]), "number": f["number"], "label": f["label"],
                    "lat": f["lat"], "lon": f["lon"], "onMap": f["onMap"]}.items() if v is not None}
                   for f in filas if f["tipo"] == "PUESTO"]
        activos = [{k: v for k, v in {"id": a_ids.get(f["label"]), "type": f["tipo"], "label": f["label"],
                    "lat": f["lat"], "lon": f["lon"]}.items() if v is not None}
                   for f in filas if f["tipo"] != "PUESTO"]
        api.call("PUT", f"/trazados/{tz}/puestos", puestos, escribe=True)
        api.call("PUT", f"/trazados/{tz}/assets", activos, escribe=True)
        contar("actualizados")
        print(f"  posiciones {clave}/{trazado}: {len(puestos)} puestos, {len(activos)} activos")


def cargar_dibujos(api, ids_circuito, dibujos, contar):
    """Dibujo (vértices lat/lon) de cada trazado; solo se manda si cambió."""
    for (clave, trazado), d in dibujos.items():
        tz = id_trazado(api, ids_circuito[clave], trazado)
        if tz is None:
            print(f"  dibujo {clave}/{trazado}: el trazado aún no existe (se carga en la siguiente corrida)")
            continue
        vivo = (api.call("GET", f"/trazados/{tz}/path") or {}).get("geo", [])
        if len(vivo) == len(d["geo"]) and all(abs(a["lat"] - b["lat"]) < 1e-6 and abs(a["lon"] - b["lon"]) < 1e-6
                                              for a, b in zip(vivo, d["geo"])):
            contar("sin cambio")
            continue
        api.call("PUT", f"/trazados/{tz}/path", {"geo": d["geo"]}, escribe=True)
        contar("actualizados" if vivo else "creados")
        print(f"  dibujo {clave}/{trazado}: {len(d['geo'])} vértices")


def cargar_logos(api, kind, ids, logos, recargar, contar):
    """Logo de cada circuito (kind `circuit`) o campeonato (kind `series`). Solo se sube si
    aún NO tiene logo (no pisa uno subido a mano en el admin), salvo con --recargar-logos."""
    for clave, archivo in logos.items():
        oid = ids.get(clave)
        if oid is None or str(oid).startswith("(nuevo"):
            contar("creados")  # dry-run: aún no existe
            continue
        if not recargar and api.existe(f"/images/{kind}/{oid}/thumb"):
            contar("sin cambio")
            continue
        tipo = "image/png" if archivo.suffix.lower() == ".png" else "image/jpeg"
        api.subir(f"/images/{kind}/{oid}", archivo.read_bytes(), tipo)
        contar("actualizados")
        print(f"  logo {clave}: {archivo.name}")


def geojson(props, puntos):
    """Feature LineString legible: un vértice [lon, lat] por renglón (diffs de git útiles)."""
    verts = ",\n".join(f"      [{lon:.7f}, {lat:.7f}]" for lat, lon in puntos)
    return ('{\n  "type": "Feature",\n  "properties": ' + json.dumps(props, ensure_ascii=False) +
            ',\n  "geometry": {\n    "type": "LineString",\n    "coordinates": [\n' + verts + "\n    ]\n  }\n}\n")


def exportar_dibujos(api, circuitos, trazados):
    """Trae a data/ el dibujo de cada trazado de trazados.csv que tenga uno en el sistema
    (p. ej. el que se pintó en el editor del admin web). Conserva la fuente del archivo."""
    existentes = {c["name"]: c["id"] for c in api.call("GET", "/circuits")}
    for t in trazados:
        cid = existentes.get(circuitos[t["circuito"]]["name"])
        tz = cid and id_trazado(api, cid, t["name"])
        geo = (api.call("GET", f"/trazados/{tz}/path") or {}).get("geo", []) if tz else []
        if not geo:
            continue
        ruta = RAIZ / "circuitos" / "dibujos" / t["circuito"] / f"{slug(t['name'])}.geojson"
        props = {"circuito": t["circuito"], "trazado": t["name"], "fuente": "dibujado en el admin web sobre OpenStreetMap"}
        if ruta.exists():
            props |= json.loads(ruta.read_text(encoding="utf-8")).get("properties", {})
        ruta.parent.mkdir(parents=True, exist_ok=True)
        ruta.write_text(geojson(props, [(p["lat"], p["lon"]) for p in geo]), encoding="utf-8")
        print(f"{ruta.relative_to(RAIZ.parent)}: {len(geo)} vértices")


def exportar_posiciones(api, circuitos, trazados):
    """Reescribe los CSV de posiciones con las coordenadas actuales del sistema (tras
    acomodarlas en el editor del admin web). Conserva el orden, tipo y fuente del CSV."""
    existentes = {c["name"]: c["id"] for c in api.call("GET", "/circuits")}
    for ruta in archivos_posiciones():
        clave = ruta.parent.name
        t = next((t for t in trazados if t["circuito"] == clave and slug(t["name"]) == ruta.stem), None)
        tz = t and id_trazado(api, existentes.get(circuitos[clave]["name"]), t["name"])
        if not tz:
            raise Error(f"{ruta}: el trazado no existe en el sistema")
        vivo = {p["label"]: p for p in api.call("GET", f"/trazados/{tz}/puestos")}
        vivo |= {a["label"]: a for a in api.call("GET", f"/trazados/{tz}/assets")}
        with open(ruta, newline="", encoding="utf-8") as fh:
            filas = list(csv.DictReader(fh))
        cambios = 0
        for f in filas:
            v = vivo.get(f["etiqueta"])
            if v and v.get("lat") is not None:
                lat, lon = f"{v['lat']:.7f}", f"{v['lon']:.7f}"
                if (lat, lon) != (f["lat"], f["lon"]):
                    f["lat"], f["lon"] = lat, lon
                    cambios += 1
        # Puestos que el sistema creó por su cuenta (propuestos por oficiales y aprobados
        # en el admin): se agregan al CSV para que la siguiente carga no los archive.
        conocidas = {f["etiqueta"] for f in filas}
        nuevos = [p for p in api.call("GET", f"/trazados/{tz}/puestos") if p["label"] not in conocidas]
        for p in sorted(nuevos, key=lambda p: p.get("number", 0)):
            en_mapa = p.get("onMap", True) and p.get("lat") is not None
            filas.append({**{k: "" for k in filas[0].keys()}, "tipo": "PUESTO", "etiqueta": p["label"],
                          "numero": str(p.get("number", "")),
                          "lat": f"{p['lat']:.7f}" if en_mapa else "", "lon": f"{p['lon']:.7f}" if en_mapa else "",
                          "en_mapa": "si" if en_mapa else "no",
                          "fuente": "Propuesto por oficiales desde la app (registro por honor) y aprobado en el admin"})
            cambios += 1
        with open(ruta, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(filas[0].keys()))
            w.writeheader()
            w.writerows(filas)
        print(f"{ruta.relative_to(RAIZ.parent)}: {cambios} posiciones actualizadas desde el sistema ({len(nuevos)} puestos nuevos)")


def cargar_eventos(api, ids_circuito, posiciones, eventos, mapeo, contar):
    """Eventos (llave = nombre) y, si hay roster privado, oficiales faltantes (por OMDAI) y
    la lista COMPLETA de asignaciones (reemplazo; conserva ids de las que ya existían)."""
    if not eventos:
        return
    campeonatos = {(c["name"], c["seasonLabel"]): c["id"] for c in api.call("GET", "/championships")}
    existentes = {e["name"]: e for e in api.call("GET", "/events")}
    oficiales = {o["omdaiId"]: o for o in api.call("GET", "/officers")}
    tipo_de = {(clave, tz, f["label"]): f["tipo"] for (clave, tz), filas in posiciones.items() for f in filas}
    for ev in eventos:
        cid = ids_circuito[ev["_circuito"]]
        tz_ids = [id_trazado(api, cid, t) for t in ev["_trazados"]]
        if None in tz_ids:
            print(f"  evento {ev['name']}: sus trazados aún no existen (siguiente corrida)")
            continue
        cuerpo = {"name": ev["name"], "startsOn": ev["startsOn"], "endsOn": ev["endsOn"], "circuitId": cid,
                  "trazadoIds": tz_ids, "championshipIds": [campeonatos[c] for c in ev["_campeonatos"] if c in campeonatos],
                  "selfRegistration": ev["selfRegistration"]}
        actual = existentes.get(ev["name"])
        if actual is None:
            r = api.call("POST", "/events", cuerpo, escribe=True)
            ev_id = r.get("id") if not api.dry else None
            contar("creados")
        else:
            ev_id = actual["id"]
            igual = all(actual.get(k) == cuerpo[k] for k in ("startsOn", "endsOn", "circuitId", "trazadoIds", "championshipIds")) \
                and actual.get("selfRegistration", True) == cuerpo["selfRegistration"]
            if not igual:
                api.call("PUT", f"/events/{ev_id}", {**cuerpo, "id": ev_id}, escribe=True)
                contar("actualizados")
            else:
                contar("sin cambio")
        if not ev["_roster"]:
            print(f"  evento {ev['name']}: sin roster privado")
            continue
        # Posiciones del evento por etiqueta (puestos + activos de sus trazados)
        pos_id, pos_tipo = {}, {}
        for tz, nombre in zip(tz_ids, ev["_trazados"]):
            for p in api.call("GET", f"/trazados/{tz}/puestos"):
                pos_id[p["label"]] = p["id"]; pos_tipo[p["label"]] = "PUESTO"
            for a in api.call("GET", f"/trazados/{tz}/assets"):
                pos_id[a["label"]] = a["id"]; pos_tipo[a["label"]] = a["type"]
        asignaciones, nuevos = [], 0
        for r in ev["_roster"]:
            if r["posicion"] not in pos_id:
                raise Error(f"{r['_donde']}: la posición '{r['posicion']}' no existe en los trazados del evento")
            rol = mapeo.get((r["posicion"], r["rol_origen"])) or mapeo.get((pos_tipo[r["posicion"]], r["rol_origen"]))
            if rol is None:
                raise Error(f"{r['_donde']}: sin mapeo para rol '{r['rol_origen']}' en {pos_tipo[r['posicion']]} (data/privado/eventos/roles-omdai.csv)")
            of = oficiales.get(r["omdai"])
            if of is None:
                if api.dry:
                    nuevos += 1
                    continue
                res = api.call("POST", "/officers", {"omdaiId": r["omdai"], "displayName": r["nombre"],
                                                      "systemRole": "OFICIAL", "status": "ACTIVE", "stats": {}}, escribe=True)
                of = {"id": res["id"], "omdaiId": r["omdai"]}
                oficiales[r["omdai"]] = of
                nuevos += 1
            asignaciones.append({"officerId": of["id"], "role": rol, "puestoId": pos_id[r["posicion"]], "shift": "Día completo"})
        if ev_id and not (api.dry and nuevos):
            previas = {(a["officerId"], a["puestoId"]): a["id"] for a in api.call("GET", f"/events/{ev_id}/assignments")}
            for a in asignaciones:
                if (a["officerId"], a["puestoId"]) in previas:
                    a["id"] = previas[(a["officerId"], a["puestoId"])]
            api.call("PUT", f"/events/{ev_id}/assignments", asignaciones, escribe=True)
        stats_ev = f"{len(ev['_roster'])} asignaciones, {nuevos} oficiales nuevos"
        print(f"  evento {ev['name']}: {stats_ev}")


def cargar(api, circuitos, trazados, posiciones, campeonatos, cuentas, eventos=(), mapeo=None,
           dibujos=None, logos=None, recargar_logos=False, fuentes=(), logos_campeonatos=None,
           archivar_sobrantes=False, lecturas=None):
    stats = {"creados": 0, "actualizados": 0, "sin cambio": 0, "calendarios": 0}

    def contar(accion):
        stats[accion] += 1

    # 1) Circuitos (llave = nombre)
    existentes = {c["name"]: c for c in api.call("GET", "/circuits")}
    ids_circuito = {}
    for clave, c in circuitos.items():
        actual = existentes.get(c["name"])
        if actual is None:
            r = api.call("POST", "/circuits", c, escribe=True)
            ids_circuito[clave] = r.get("id") if not api.dry else f"(nuevo:{clave})"
            contar("creados")
        else:
            ids_circuito[clave] = actual["id"]
            if actual.get("location") != c["location"] or actual.get("country") != c["country"]:
                api.call("PUT", f"/circuits/{actual['id']}", c, escribe=True)
                contar("actualizados")
            else:
                contar("sin cambio")
    print(f"circuitos: {len(circuitos)} en archivo")

    # 1b) Trazados (llave = circuito + nombre); el dibujo y los puestos van aparte
    for t in trazados:
        circuit_id = ids_circuito[t["circuito"]]
        cuerpo = {k: t[k] for k in ("name", "lengthM", "curves", "direction")} | {"circuitId": circuit_id}
        existentes_t = {} if str(circuit_id).startswith("(nuevo") else {x["name"]: x for x in api.call("GET", f"/circuits/{circuit_id}/trazados")}
        actual = existentes_t.get(t["name"])
        if actual is None:
            if not str(circuit_id).startswith("(nuevo"):
                api.call("POST", "/trazados", cuerpo, escribe=True)
            contar("creados")
        elif any(actual.get(k) != cuerpo[k] for k in ("lengthM", "curves", "direction")):
            api.call("PUT", f"/trazados/{actual['id']}", {**actual, **cuerpo}, escribe=True)
            contar("actualizados")
        else:
            contar("sin cambio")
    if trazados:
        print(f"trazados: {len(trazados)} en archivo")

    # 1c) Dibujo de cada trazado y logos de los circuitos
    cargar_dibujos(api, ids_circuito, dibujos or {}, contar)
    cargar_logos(api, "circuit", ids_circuito, logos or {}, recargar_logos, contar)

    # 1d) Puestos y activos de cada trazado (después del dibujo: su punto se deriva de él)
    cargar_posiciones(api, ids_circuito, posiciones, contar, archivar_sobrantes)

    # 2) Campeonatos (series) → temporadas → categorías → calendario
    series = {s["name"]: s["id"] for s in api.call("GET", "/series")}
    existentes = {(c["name"], c["seasonLabel"]): c for c in api.call("GET", "/championships")}
    for (nombre, temporada), cats in campeonatos.items():
        if nombre not in series:
            r = api.call("POST", "/series", {"name": nombre}, escribe=True)
            series[nombre] = r.get("id") if not api.dry else None
            contar("creados")
        actual = existentes.get((nombre, temporada))
        if actual is None:
            champ_id = None
            if series[nombre]:
                r = api.call("POST", "/championships", {"seriesId": series[nombre], "seasonLabel": temporada}, escribe=True)
                champ_id = r.get("id") if not api.dry else None
            contar("creados")
        else:
            champ_id = actual["id"]
            contar("sin cambio")
        cat_existentes = {c["name"]: c for c in api.call("GET", f"/championships/{champ_id}/categories")} if champ_id else {}
        for cat, fechas in cats.items():
            cat_id = cat_existentes.get(cat, {}).get("id")
            if cat_id is None:
                if champ_id:
                    r = api.call("POST", "/categories", {"championshipId": champ_id, "name": cat}, escribe=True)
                    cat_id = r.get("id") if not api.dry else None
                contar("creados")
            else:
                contar("sin cambio")
            cuerpo = []
            for f in fechas:
                fila = {k: v for k, v in f.items() if not k.startswith("_")}
                if f["_circuito"]:
                    fila["circuitId"] = ids_circuito[f["_circuito"]]
                cuerpo.append(fila)
            # En dry-run, lo que aún no existe (campeonato/categoría/circuito nuevos) no tiene
            # id: esas fechas solo se validan localmente.
            pendiente = any(str(x.get("circuitId", "")).startswith("(nuevo") for x in cuerpo)
            if cat_id and not pendiente:
                api.call("PUT", f"/categories/{cat_id}/rounds", cuerpo, escribe=True)
            stats["calendarios"] += 1
            print(f"  {nombre} {temporada} · {cat}: {len(fechas)} fechas")

    # 2a) Logo de cada campeonato (es de la serie, no de la temporada)
    cargar_logos(api, "series", series, logos_campeonatos or {}, recargar_logos, contar)

    # 2b) Posiciones automáticas (fuente externa por categoría)
    cargar_fuentes_posiciones(api, list(fuentes), contar)

    # 2c) Tablas leídas de imágenes (después de las fuentes: al subirlas se publican)
    cargar_lecturas(api, lecturas or [], contar)

    # 3) Datos privados (no versionados): oficiales + cuentas
    if cuentas:
        oficiales = {o["omdaiId"]: o for o in api.call("GET", "/officers")}
        for c in cuentas:
            cuerpo = {
                "omdaiId": c["omdai"], "displayName": c["nombre"], "assignedArea": c["area"],
                "systemRole": "OFICIAL", "status": "ACTIVE", "stats": {"activeSince": c["activo_desde"]},
            }
            actual = oficiales.get(c["omdai"])
            if actual is None:
                r = api.call("POST", "/officers", cuerpo, escribe=True)
                officer_id = r.get("id") if not api.dry else None
                contar("creados")
            else:
                officer_id = actual["id"]
                igual = (actual.get("displayName") == c["nombre"] and actual.get("assignedArea") == c["area"]
                         and (actual.get("stats") or {}).get("activeSince") == c["activo_desde"])
                if igual:
                    contar("sin cambio")
                else:
                    api.call("PUT", f"/officers/{officer_id}", {**actual, **cuerpo, "stats": {**actual.get("stats", {}), **cuerpo["stats"]}}, escribe=True)
                    contar("actualizados")
            if officer_id:
                api.call("PUT", f"/accounts/{urllib.parse.quote(c['correo'])}", {"officerId": officer_id, "status": c["estatus"]}, escribe=True)
        print(f"cuentas privadas: {len(cuentas)}")

    # 4) Eventos (+ roster privado → oficiales y asignaciones)
    cargar_eventos(api, ids_circuito, posiciones, list(eventos), mapeo or {}, contar)
    return stats


def main():
    p = argparse.ArgumentParser(description="Carga data/ al sistema por la API admin.")
    p.add_argument("--api", default=os.environ.get("EL_PUESTO_API"), help="URL base del backend (EL_PUESTO_API)")
    p.add_argument("--key", default=os.environ.get("EL_PUESTO_ADMIN_KEY"), help="X-Admin-Key (EL_PUESTO_ADMIN_KEY)")
    p.add_argument("--dry-run", action="store_true", help="valida contra la API sin escribir nada")
    p.add_argument("--solo-validar", action="store_true", help="solo valida los archivos, sin tocar la API")
    p.add_argument("--exportar-posiciones", action="store_true", help="trae las coordenadas actuales del sistema a los CSV de posiciones")
    p.add_argument("--exportar-dibujos", action="store_true", help="trae los dibujos de trazados del sistema a circuitos/dibujos/")
    p.add_argument("--recargar-logos", action="store_true", help="sube los logos aunque el circuito o campeonato ya tenga uno")
    p.add_argument("--archivar-sobrantes", action="store_true",
                   help="archiva los puestos del sistema que no estén en los CSV de posiciones (p. ej. aprobados desde propuestas); sin esto la carga se detiene")
    a = p.parse_args()
    try:
        circuitos = leer_circuitos()
        trazados = leer_trazados(circuitos)
        posiciones = leer_posiciones(circuitos, trazados)
        dibujos = leer_dibujos(circuitos, trazados)
        logos = leer_logos("circuitos", "circuito", circuitos, "circuitos.csv")
        campeonatos = leer_calendarios(circuitos)
        logos_campeonatos = leer_logos("campeonatos", "campeonato", {n for n, _ in campeonatos}, "los calendarios")
        fuentes = leer_fuentes_posiciones(campeonatos)
        lecturas = leer_lecturas(campeonatos)
        cuentas = leer_privado()
        eventos, mapeo = leer_eventos(circuitos, trazados, campeonatos)
        total = sum(len(f) for cats in campeonatos.values() for f in cats.values())
        print(f"archivos OK: {len(circuitos)} circuitos, {len(trazados)} trazados ({len(dibujos)} con dibujo), {len(logos)} logos de circuito, {sum(len(v) for v in posiciones.values())} posiciones, {len({n for n, _ in campeonatos})} campeonatos ({len(logos_campeonatos)} con logo) en {len(campeonatos)} temporadas, {total} fechas, {len(fuentes)} con posiciones automáticas ({len(lecturas)} leídas de imágenes), {len(eventos)} eventos ({sum(len(e['_roster']) for e in eventos)} asignaciones privadas), {len(cuentas)} cuentas privadas")
        if a.solo_validar:
            return 0
        if not a.api or not a.key:
            raise Error("falta --api/--key (o EL_PUESTO_API / EL_PUESTO_ADMIN_KEY; carga ./setenv)")
        if a.exportar_posiciones:
            exportar_posiciones(Api(a.api, a.key, False), circuitos, trazados)
            return 0
        if a.exportar_dibujos:
            exportar_dibujos(Api(a.api, a.key, False), circuitos, trazados)
            return 0
        stats = cargar(Api(a.api, a.key, a.dry_run), circuitos, trazados, posiciones, campeonatos, cuentas, eventos, mapeo,
                       dibujos, logos, a.recargar_logos, fuentes, logos_campeonatos, a.archivar_sobrantes, lecturas)
        print(("[dry-run] " if a.dry_run else "") + ", ".join(f"{k}: {v}" for k, v in stats.items()))
        return 0
    except Error as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
