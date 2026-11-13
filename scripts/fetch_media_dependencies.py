"""Resolve the pinned Media3 runtime graph from publisher Maven repositories.

The generated lock contains hashes; the application build consumes it without
changing versions. This is a small Maven POM resolver, not a Gradle replacement.
"""
from pathlib import Path
import hashlib, json, re, urllib.request, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT/'tools/java-dependencies'
NS={'m':'http://maven.apache.org/POM/4.0.0'}
MODELS={}
SELECTED={}
EXCLUDED={('com.google.j2objc','j2objc-annotations'),('org.checkerframework','checker-compat-qual'),('com.google.code.findbugs','jsr305'),('org.codehaus.mojo','animal-sniffer-annotations'),('org.checkerframework','checker-qual'),('com.google.errorprone','error_prone_annotations')}

def base(g):return 'https://dl.google.com/dl/android/maven2/' if g.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/'
def url(g,a,v,ext):return base(g)+g.replace('.','/')+'/'+a+'/'+v+'/'+a+'-'+v+'.'+ext
def fetch(g,a,v,ext):
    CACHE.mkdir(exist_ok=True,parents=True)
    path=CACHE/(a+'-'+v+'.'+ext)
    if not path.exists():path.write_bytes(urllib.request.urlopen(url(g,a,v,ext),timeout=45).read())
    return path
def value(e,name,default=''):return e.findtext('m:'+name,default,NS)
def expand(text,props):
    for _ in range(10):
        result=re.sub(r'\$\{([^}]+)\}',lambda m:props.get(m[1],m[0]),text)
        if text==result:break
        text=result
    if text.startswith('[') and text.endswith(']'):text=text[1:-1]
    return text
def model(g,a,v):
    key=(g,a,v)
    if key in MODELS:return MODELS[key]
    e=ET.parse(fetch(g,a,v,'pom')).getroot();props={};managed={};inherited=[];inherited_licenses=[]
    p=e.find('m:parent',NS)
    if p is not None:
        parent=model(value(p,'groupId'),value(p,'artifactId'),value(p,'version'))
        props.update(parent['props']);managed.update(parent['managed']);inherited=parent['deps'][:];inherited_licenses=parent['licenses'][:]
    props.update({'project.groupId':g,'project.artifactId':a,'project.version':v,'version':v})
    properties=e.find('m:properties',NS)
    if properties is not None:
        for item in properties:props[item.tag.split('}')[-1]]=expand(item.text or '',props)
    for d in e.findall('m:dependencyManagement/m:dependencies/m:dependency',NS):
        dg,da,dv=[expand(value(d,n),props) for n in ('groupId','artifactId','version')]
        if value(d,'scope')=='import' and value(d,'type')=='pom':managed.update(model(dg,da,dv)['managed'])
        else:managed[(dg,da)]=dv
    deps=inherited
    for d in e.findall('m:dependencies/m:dependency',NS):
        if value(d,'scope') in ('test','provided','system') or value(d,'optional')=='true':continue
        dg,da=[expand(value(d,n),props) for n in ('groupId','artifactId')]
        if (dg,da) in EXCLUDED:continue
        dv=expand(value(d,'version',managed.get((dg,da),'')),props)
        if not dv or '${' in dv:raise RuntimeError('Unresolved dependency '+str((dg,da,dv,key)))
        deps.append((dg,da,dv))
    result={'props':props,'managed':managed,'deps':deps,'type':value(e,'packaging','jar'),'licenses':[value(l,'name') for l in e.findall('m:licenses/m:license',NS)] or inherited_licenses}
    MODELS[key]=result
    return result
def version_key(v):return tuple(int(x) for x in re.findall(r'\d+',v))
def resolve(g,a,v):
    old=SELECTED.get((g,a))
    if old is not None and version_key(old)>=version_key(v):return
    SELECTED[(g,a)]=v
    m=model(g,a,v)
    for dg,da,dv in m['deps']:resolve(dg,da,dv)
def main():
    for a in ('media3-exoplayer','media3-exoplayer-hls','media3-exoplayer-dash','media3-ui','media3-exoplayer-rtsp','media3-effect'):resolve('androidx.media3',a,'1.11.1')
    # Annotation 1.6 publishes multiplatform metadata; Java consumes its JVM variant.
    resolve('androidx.annotation','annotation-jvm','1.6.0')
    locked=[]
    for (g,a),v in sorted(SELECTED.items()):
        m=model(g,a,v);ext=m['type']
        if ext=='pom':continue
        if ext=='bundle':ext='jar'
        if ext not in ('jar','aar'):raise RuntimeError('Unsupported packaging '+ext)
        path=fetch(g,a,v,ext)
        locked.append({'name':a,'group':g,'version':v,'license':'; '.join(m['licenses']) or 'See publisher POM','url':url(g,a,v,ext),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (ROOT/'android/media-dependencies.json').write_text(json.dumps(locked,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'artifacts':len(locked),'coordinates':[x['group']+':'+x['name']+':'+x['version'] for x in locked]},indent=2))
if __name__=='__main__':main()

