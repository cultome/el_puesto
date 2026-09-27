import re,html,sys
for f in sys.argv[1:]:
    s=open(f,encoding='utf-8').read()
    # find main content: after the menu "Contacto" link
    i=s.find('id="contenido')
    body=re.sub(r'<(script|style)[^>]*>.*?</\1>','',s,flags=re.S)
    txt=re.sub(r'<[^>]+>','\n',body)
    txt=html.unescape(txt)
    lines=[l.strip() for l in txt.split('\n') if l.strip()]
    try:
        k=lines.index('Contacto')
    except ValueError: k=0
    print('=====',f)
    print(' | '.join(lines[k+1:k+30]))
    imgs=re.findall(r'<img[^>]+>',s)
    for m in imgs:
        if 'banner/' in m or 'logo/2.png' in m: continue
        print('  IMG',m)
    for m in re.findall(r'href="([^"]+)"',s):
        if 'repository' in m and 'logo' not in m: print('  LINK',m)
