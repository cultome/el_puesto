"""Prototipo: tabla de pilotos desde la página pública SSR de indycar.com / indynxt.com (/standings/<año>)."""
import re, html, json, sys
from html.parser import HTMLParser

def rows(path):
    s = open(path, encoding='utf-8').read()
    out = []
    for chunk in s.split('<tr class="data-table-driver-row">')[1:]:
        chunk = chunk.split('</tr>')[0]
        tds = re.findall(r'<td[^>]*>(.*?)</td>', chunk, re.S)
        rank = int(re.sub(r'<[^>]+>', '', tds[0]).strip())
        dd = json.loads(html.unescape(re.search(r"data-driver-data='([^']*)'", chunk).group(1)))
        pdata = re.search(r"data-points-data='([^']*)'", chunk)
        pd = json.loads(html.unescape(pdata.group(1))) if pdata else None
        endplate = dd.get('endplateImg') or ''
        m = re.search(r'/Endplates/[^/]+/([0-9]+)-', endplate)
        team = re.search(r'alt="([^"]*) Logo\s*"', tds[3])
        name = html.unescape(re.sub(r'<[^>]+>', '', re.search(r'<p>(.*?)</p>', tds[2], re.S).group(1))).strip()
        points_txt = re.sub(r'<[^>]+>', '', tds[5 if len(tds) >= 13 else 4]).strip()
        out.append(dict(rank=rank, name=name, first=dd.get('firstName'), last=dd.get('lastName'),
                        slug=(dd.get('driverUrl') or '').rsplit('/', 1)[-1], number=m.group(1) if m else None,
                        endplate=endplate.rsplit('/', 1)[-1], team=html.unescape(team.group(1)).strip() if team else None,
                        points=dd.get('points'), points_cell=points_txt,
                        events=[(html.unescape(e['name']), e.get('pos'), e.get('pts')) for e in (pd or {}).get('events', [])]))
    return out

if __name__ == '__main__':
    for r in rows(sys.argv[1]):
        print(r['rank'], r['number'], r['name'], '|', r['team'], '|', r['points'], r['points_cell'], r['slug'], r['endplate'], len(r['events']))
