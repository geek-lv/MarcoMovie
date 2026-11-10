from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json, os, subprocess, threading, hashlib, urllib.request
ROOT=Path(__file__).resolve().parents[2]
JDK=Path(os.environ.get('MARCOMOVIE_JDK',os.environ.get('JAVA_HOME', 'missing-jdk')))
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_HEAD(self):
        self.send_response(200);self.send_header('Content-Type','text/html' if self.path=='/web' else 'video/mp4');self.end_headers()
    def do_GET(self):
        doc={'class':[{'type_id':'7','type_name':'Mock'}],'page':1,'pagecount':2,'list':[{'vod_id':'42','vod_name':'模拟影片','vod_play_from':'线路一$$$线路二','vod_play_url':'上集$http://example.test/v.mp4?x=1$2#下集$http://example.test/v2.mp4$$$全集$http://example.test/full.m3u8'}]}
        raw=json.dumps(doc).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
test=ROOT/'android/tests';classes=test/'classes';classes.mkdir(exist_ok=True)
jar=test/'lib/json-20240303.jar'
if not jar.exists():
    jar.parent.mkdir(exist_ok=True);jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read())
if hashlib.sha256(jar.read_bytes()).hexdigest()!='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':raise RuntimeError('Test JSON library digest mismatch')
files=[*sorted((ROOT/'android/src/main/java/com/pilipala/marcomovie').glob('*.java'))]
# NSFW 过滤（Cms.listing 出口挂载 Safety）与 AppLog 也进入编译单元；
# safety 包引用 android.content.*，所以 classpath 需要 android.jar（仅编译期，JVM 运行不触发）。
files=[p for p in files if p.name in ('Cms.java','RequestScope.java','SourceEngine.java','JsonCmsEngine.java','VideoFilters.java','Safety.java','AppLog.java')]
files+=sorted((ROOT/'android/src/main/java/com/pilipala/marcomovie/safety').glob('*.java'))
ANDROID_JAR=Path(os.environ.get('MARCOMOVIE_ANDROID_JAR',str(ROOT/'tools/android-35/android.jar')))
if not ANDROID_JAR.exists():
    import sys
    sys.path.insert(0,str(ROOT/'scripts'))
    from prepare_android_platform import prepare
    prepare()
try:
    subprocess.run([str(JDK/'bin'/('javac.exe' if os.name=='nt' else 'javac')),'-encoding','UTF-8','-cp',str(jar)+os.pathsep+str(ANDROID_JAR),'-d',str(classes),str(ROOT/'shared/java/com/pilipala/source/Warehouse.java'),*[str(p) for p in files],str(test/'CmsChecks.java')],check=True)
    r=subprocess.run([str(JDK/'bin'/('java.exe' if os.name=='nt' else 'java')),'-cp',str(classes)+os.pathsep+str(jar),'CmsChecks','unused',f'http://127.0.0.1:{server.server_port}/'],check=True,text=True,encoding='utf8',capture_output=True)
    print(r.stdout);(test/'results.txt').write_text(r.stdout,encoding='utf8')
finally:server.shutdown()
