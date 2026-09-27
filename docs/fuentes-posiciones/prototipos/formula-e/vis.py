import sys
from html.parser import HTMLParser
class P(HTMLParser):
    def __init__(s): super().__init__(); s.t=[]; s.skip=0
    def handle_starttag(s,tag,a):
        if tag in('script','style'): s.skip+=1
    def handle_endtag(s,tag):
        if tag in('script','style'): s.skip-=1
    def handle_data(s,d):
        if not s.skip and d.strip(): s.t.append(d.strip())
p=P(); p.feed(open(sys.argv[1],encoding='utf-8').read()); print(' | '.join(p.t))
