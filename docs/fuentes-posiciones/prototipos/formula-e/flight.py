import json,sys,re
PUSH='self.__next_f.push('
def json_end(s,start):
    if start>=len(s) or s[start] not in '{[': return None
    depth=0; ins=False; esc=False
    for k in range(start,len(s)):
        c=s[k]
        if ins:
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=='"': ins=False
            continue
        if c=='"': ins=True
        elif c in '{[': depth+=1
        elif c in '}]':
            depth-=1
            if depth==0: return k+1
    return None
def flight(html):
    out=[]; i=html.indexOf(PUSH) if False else html.find(PUSH)
    while i>=0:
        st=i+len(PUSH); en=json_end(html,st)
        if en is None: break
        ch=json.loads(html[st:en])
        if len(ch)>1 and isinstance(ch[1],str): out.append(ch[1])
        i=html.find(PUSH,en)
    return ''.join(out)
if __name__=='__main__':
    f=flight(open(sys.argv[1],encoding='utf-8').read())
    sys.stdout.write(f)
