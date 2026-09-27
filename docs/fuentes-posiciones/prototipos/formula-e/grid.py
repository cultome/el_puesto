import json,sys
from flight import json_end, flight
def grid_rows(fl):
    i=fl.find('"gridPanel":')
    if i<0: return None
    i+=len('"gridPanel":'); e=json_end(fl,i); g=json.loads(fl[i:e])
    found=[]
    def walk(n):
        if isinstance(n,list):
            if len(n)==4 and n[0]=='$' and isinstance(n[3],dict):
                if n[3].get('entity')=='driver': found.append(n[3])
                walk(n[3].get('children'))
            else:
                for x in n: walk(x)
    walk(g); return found
if __name__=='__main__':
    rows=grid_rows(open(sys.argv[1],encoding='utf-8').read())
    for r in rows:
        rn=[x['roundNum'] for x in r['rounds']]
        print(r['position'],r['name'],(r['media'].get('href') if isinstance(r.get('media'),dict) else r.get('media')),r['points'],len(rn),min(rn) if rn else None,max(rn) if rn else None, sum(x['points'] or 0 for x in r['rounds']), [ (x['roundNum'],x['status']) for x in r['rounds'] if x['status']])
