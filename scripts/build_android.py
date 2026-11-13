"""Build the independent Marco Movie native preview using an existing Android SDK."""
from pathlib import Path
import hashlib, json, os, re, subprocess, zipfile, urllib.request
import xml.etree.ElementTree as ET
from build_catpaw_android import build as build_catpaw, assets_for_package
from build_quickjs_deadline import build as build_quickjs_deadline
from android_package_names import package_name, compatibility_alias

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'android/src/main'
BUILD = ROOT / 'android/build'
DIST = ROOT / 'dist'
JDK = Path(os.environ.get('MARCOMOVIE_JDK', os.environ.get('JAVA_HOME', 'missing-jdk')))
SDK = Path(os.environ.get('MARCOMOVIE_BUILD_TOOLS', 'missing-build-tools'))
ANDROID = Path(os.environ.get('MARCOMOVIE_ANDROID_JAR', str(ROOT/'tools/android-35/android.jar')))
ENV = dict(os.environ, JAVA_HOME=str(JDK), PATH=str(JDK/'bin')+os.pathsep+os.environ.get('PATH',''))
# Development default only. A release key must supply its own password through
# MARCOMOVIE_KEY_PASSWORD; an externally provided value is never overridden.
ENV.setdefault('MARCOMOVIE_KEY_PASSWORD', 'android')

def exe(parent,name):
    return parent / (name + ('.exe' if os.name=='nt' else ''))

def run(*args, capture=False):
    r = subprocess.run([str(x) for x in args], cwd=ROOT, env=ENV, check=True, text=True,
                       encoding='utf-8', errors='replace', stdout=subprocess.PIPE if capture else None,
                       stderr=subprocess.STDOUT if capture else None)
    return r.stdout if capture else None

def main():
    from prepare_python_html import verify as verify_python_html
    verify_python_html()
    from prepare_python_modules import verify as verify_python_modules
    verify_python_modules()
    if 'MARCOMOVIE_ANDROID_JAR' not in os.environ:
        from prepare_android_platform import prepare
        prepare()
    dependencies=[];native_files={};native_files_mobile={};apk_resources={};resource_dirs=[];packages=[]
    items=json.loads((ROOT/'android/dependencies.json').read_text(encoding='utf-8'))
    for filename in ('media-dependencies.json','torrent-dependencies.json','decoder-dependencies.json'):
        manifest=ROOT/'android'/filename
        if manifest.exists():items+=json.loads(manifest.read_text(encoding='utf-8'))
    for item in items:
        jar=ROOT/'tools/java-dependencies'/item['url'].rsplit('/',1)[1];jar.parent.mkdir(parents=True,exist_ok=True)
        if not jar.exists():jar.write_bytes(urllib.request.urlopen(item['url'],timeout=30).read())
        if hashlib.sha256(jar.read_bytes()).hexdigest()!=item['sha256']:raise RuntimeError('Dependency digest mismatch: '+item['name'])
        if jar.suffix=='.aar':
            unpack=BUILD/'aar'/jar.stem;unpack.mkdir(parents=True,exist_ok=True)
            with zipfile.ZipFile(jar) as z:
                classes=unpack/'classes.jar';classes.write_bytes(z.read('classes.jar'));dependencies.append(classes)
                manifest=ET.fromstring(z.read('AndroidManifest.xml'))
                package=manifest.get('package')
                if package:packages.append(package)
                has_arm64=any(n.startswith('jni/arm64-v8a/') and n.endswith('.so') for n in z.namelist())
                for name in z.namelist():
                    if name.startswith('jni/armeabi-v7a/') and name.endswith('.so'):
                        native_files['lib/'+name[4:]]=z.read(name)
                        if has_arm64:native_files_mobile['lib/'+name[4:]]=z.read(name)
                    if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
                        native_files_mobile['lib/'+name[4:]]=z.read(name)
                    if name.startswith('res/') and not name.endswith('/'):
                        target=(unpack/name).resolve()
                        if not target.is_relative_to(unpack.resolve()):raise RuntimeError('Invalid AAR resource path')
                        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
                if (unpack/'res').exists():resource_dirs.append(unpack/'res')
        else:
            if item.get('java_classes',True):dependencies.append(jar)
            # 纯 javac/d8 构建不会自动带上 jar 内的运行时资源。按清单把它们原路径
            # 写进 APK 根，例如 pinyin4j 的 Class.getResourceAsStream("/pinyindb/...")。
            prefixes=item.get('apk_resources') or []
            if prefixes:
                with zipfile.ZipFile(jar) as z:
                    for name in z.namelist():
                        if name.endswith('/') or name.endswith('.class'):continue
                        if any(name.startswith(p) for p in prefixes):apk_resources[name]=z.read(name)
                print('Packaged jar resources:',len(apk_resources),'entries')
            if item.get('native_members'):
                with zipfile.ZipFile(jar) as z:
                    for name in item['native_members']:
                        if not (name.startswith('lib/armeabi-v7a/') or name.startswith('lib/arm64-v8a/')) or not name.endswith('.so'):
                            raise RuntimeError('Unexpected native dependency member')
                        data=z.read(name)
                        if name.startswith('lib/armeabi-v7a/'):
                            native_files[name]=data
                            native_files_mobile[name]=data
                        elif name.startswith('lib/arm64-v8a/'):
                            native_files_mobile[name]=data
    deadline_files = build_quickjs_deadline()
    native_files.update({k:v for k,v in deadline_files.items() if k.startswith('lib/armeabi-v7a/')})
    native_files_mobile.update({k:v for k,v in deadline_files.items() if k.startswith(('lib/armeabi-v7a/','lib/arm64-v8a/'))})
    # CatPaw 服务包需要 NDK + 上游 nodejs-mobile 二进制。只用普通 JSON/JS 订阅源时
    # 可以跳过（默认行为不变）；缺 so 只影响运行时 CatPaw 引擎，不影响编译与常规播放。
    if os.environ.get('MARCOMOVIE_SKIP_CATPAW') == '1':
        catpaw_files = {}
        catpaw_assets = BUILD / 'catpaw' / 'empty-assets'
        catpaw_assets.mkdir(parents=True, exist_ok=True)
        (catpaw_assets / '.keep').write_text('CatPaw disabled for this build\n', encoding='utf-8')
        print('CatPaw: skipped (MARCOMOVIE_SKIP_CATPAW=1)')
        catpaw_tv_assets = catpaw_assets
        catpaw_mobile_assets = catpaw_assets
    else:
        catpaw_files, catpaw_assets = build_catpaw()
        native_files.update({k:v for k,v in catpaw_files.items() if k.startswith('lib/armeabi-v7a/')})
        native_files_mobile.update(catpaw_files)
        catpaw_tv_assets = assets_for_package(catpaw_assets, native_files, BUILD/'catpaw/assets-tv')
        catpaw_mobile_assets = assets_for_package(catpaw_assets, native_files_mobile, BUILD/'catpaw/assets-mobile')
    # 口播识别（sherpa-onnx + onnxruntime）的 native 库。TV 包保持纯 armeabi-v7a，
    # 手机包同时带 v7a 与 arm64-v8a；缺库时引擎初始化会被 AdSkipController 捕获，不影响播放。
    jni_root = SRC / 'jniLibs'
    for abi in ('armeabi-v7a', 'arm64-v8a'):
        abi_dir = jni_root / abi
        if not abi_dir.is_dir():
            continue
        for so in sorted(abi_dir.glob('*.so')):
            name = f'lib/{abi}/{so.name}'
            data = so.read_bytes()
            if abi == 'armeabi-v7a':
                native_files[name] = data
            native_files_mobile[name] = data
    for p in (exe(JDK/'bin','java'), exe(SDK,'aapt2'), ANDROID):
        if not p.exists(): raise RuntimeError(f'Required existing tool missing: {p}')
    for folder in (BUILD, BUILD/'classes', BUILD/'dex', DIST): folder.mkdir(parents=True, exist_ok=True)
    boundary = BUILD.resolve()
    for folder, pattern in ((BUILD/'classes','*.class'),(BUILD/'dex','*.dex')):
        for old in folder.rglob(pattern):
            if not old.resolve().is_relative_to(boundary): raise RuntimeError('Generated output leaves build directory')
            old.unlink()
    # 依赖 AAR 的 assets 合并：aapt2 不会自动带入依赖库资产。
    # media3-effect 的 assets/shaders/*.glsl 是特效管线运行时经 AssetManager 加载的，
    # 缺失会导致 DefaultShaderProgram 初始化抛 FileNotFoundException → 挂载特效的播放整体失败。
    aar_assets_dir = BUILD / 'aar-assets'
    aar_assets_dir.mkdir(parents=True, exist_ok=True)
    for a in sorted(aar_assets_dir.rglob('*')):
        if a.is_file(): a.unlink()
    for aar in sorted((ROOT/'tools'/'java-dependencies').glob('*.aar')):
        try:
            with zipfile.ZipFile(aar) as z:
                for entry in z.infolist():
                    if entry.is_dir() or not entry.filename.startswith('assets/'): continue
                    # 剥掉 AAR 内部的 assets/ 前缀：aapt2 -A 的目录内容即为资产根，
                    # 运行时 AssetManager 按「shaders/xxx.glsl」相对路径查找
                    rel = entry.filename[len('assets/'):]
                    if not rel: continue
                    dest = aar_assets_dir / rel
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(z.read(entry.filename))
        except Exception as e:
            raise RuntimeError(f'AAR assets merge failed: {aar.name}: {e}')
    run(exe(SDK,'aapt2'),'compile','--dir',SRC/'res','-o',BUILD/'resources.zip')
    overlays=[]
    for i,folder in enumerate(resource_dirs):
        resource=BUILD/f'dep-res-{i}.zip';run(exe(SDK,'aapt2'),'compile','--dir',folder,'-o',resource);overlays+=['-R',resource]
    generated=BUILD/'generated';generated.mkdir(exist_ok=True)
    for old in generated.rglob('*.java'):old.unlink()
    extra=['--extra-packages',':'.join(sorted(set(packages)))] if packages else []
    # sherpa-onnx 的 JNI 直接通过 AssetManager 读模型，未压缩更稳妥（也避免运行时解压开销）
    run(exe(SDK,'aapt2'),'link','-o',BUILD/'resources.apk','--manifest',SRC/'AndroidManifest.xml','-I',ANDROID,'-A',aar_assets_dir,'-A',SRC/'assets','-A',catpaw_tv_assets,'--java',generated,'--auto-add-overlay','-0','onnx',*extra,*overlays,'-R',BUILD/'resources.zip')
    sources=sorted((SRC/'java').rglob('*.java'))+sorted((ROOT/'shared/java').rglob('*.java'))+sorted(generated.rglob('*.java'))
    run(exe(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',os.pathsep.join(str(p) for p in [ANDROID,*dependencies]),'-d',BUILD/'classes',*sources)
    with zipfile.ZipFile(BUILD/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
        for p in (BUILD/'classes').rglob('*.class'): z.write(p,p.relative_to(BUILD/'classes').as_posix())
    run(exe(JDK/'bin','java'),'-cp',SDK/'lib/d8.jar','com.android.tools.r8.D8','--min-api','23','--lib',ANDROID,'--output',BUILD/'dex',BUILD/'classes.jar',*dependencies)
    with zipfile.ZipFile(BUILD/'resources.apk') as source, zipfile.ZipFile(BUILD/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as dest:
        for entry in source.infolist():dest.writestr(entry,source.read(entry.filename))
        for dex in (BUILD/'dex').glob('*.dex'):dest.write(dex,dex.name)
        for name,data in native_files.items():dest.writestr(name,data)
        for name,data in apk_resources.items():
            dest.writestr(zipfile.ZipInfo(name),data,compress_type=zipfile.ZIP_STORED)
    run(exe(SDK,'zipalign'),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
    release_mode = os.environ.get('MARCOMOVIE_RELEASE', '') == '1' or 'MARCOMOVIE_SIGNING_KEY' in os.environ
    key=Path(os.environ.get('MARCOMOVIE_SIGNING_KEY',str(ROOT/'android/private/development.p12')));key.parent.mkdir(parents=True,exist_ok=True)
    if not key.exists():
        if release_mode:
            raise RuntimeError(f'Release/explicit signing key missing; refusing to auto-generate: {key}')
        run(exe(JDK/'bin','keytool'),'-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','marcomovie','-storepass:env','MARCOMOVIE_KEY_PASSWORD','-keypass:env','MARCOMOVIE_KEY_PASSWORD','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Pilipala, OU=Marco Movie Preview')
    manifest_root=ET.parse(SRC/'AndroidManifest.xml').getroot()
    version=manifest_root.get('{http://schemas.android.com/apk/res/android}versionName')
    version_code=int(manifest_root.get('{http://schemas.android.com/apk/res/android}versionCode','0'))
    edition_versions_file = ROOT / 'android/edition-versions.json'
    edition_versions = json.loads(edition_versions_file.read_text(encoding='utf-8')) if edition_versions_file.exists() else {}
    tv_meta = edition_versions.get('android-tv', {})
    mobile_meta = edition_versions.get('android-mobile', {})
    tv_version = tv_meta.get('versionName', version)
    tv_version_code = int(tv_meta.get('versionCode', version_code))
    mobile_version = mobile_meta.get('versionName', version)
    mobile_version_code = int(mobile_meta.get('versionCode', version_code))
    if tv_version != version or tv_version_code != version_code:
        tv_manifest = BUILD / 'AndroidManifest.tv.xml'
        tv_xml = (SRC / 'AndroidManifest.xml').read_text(encoding='utf-8')
        tv_xml = re.sub(r'android:versionCode="[^"]+"', f'android:versionCode="{tv_version_code}"', tv_xml)
        tv_xml = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{tv_version}"', tv_xml)
        tv_manifest.write_text(tv_xml, encoding='utf-8')
        run(exe(SDK,'aapt2'),'link','-o',BUILD/'resources.apk','--manifest',tv_manifest,'-I',ANDROID,'-A',aar_assets_dir,'-A',SRC/'assets','-A',catpaw_tv_assets,'--auto-add-overlay','-0','onnx',*extra,*overlays,'-R',BUILD/'resources.zip')
        with zipfile.ZipFile(BUILD/'resources.apk') as source, zipfile.ZipFile(BUILD/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as dest:
            for entry in source.infolist():dest.writestr(entry,source.read(entry.filename))
            for dex in (BUILD/'dex').glob('*.dex'):dest.write(dex,dex.name)
            for name,data in native_files.items():dest.writestr(name,data)
        run(exe(SDK,'zipalign'),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
    version = tv_version
    version_code = tv_version_code
    apk=DIST/package_name(version, 'tv')
    run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','marcomovie','--ks-pass','env:MARCOMOVIE_KEY_PASSWORD','--out',apk,BUILD/'aligned.apk')
    signature=run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','verify','--verbose','--print-certs',apk,capture=True)
    cert_match=re.search(r'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})',signature)
    signer_sha256=cert_match.group(1).lower() if cert_match else ''
    expected_signer=os.environ.get('MARCOMOVIE_EXPECTED_SIGNER_SHA256','2549f01e89c41201ea171847048228c6df79a7c57fff2ec9a439195c757a80de' if os.environ.get('MARCOMOVIE_RELEASE','')=='1' else '').strip().lower()
    if expected_signer and signer_sha256!=expected_signer:
        raise RuntimeError(f'APK signer certificate mismatch: got {signer_sha256}, expected {expected_signer}')
    alignment=run(exe(SDK,'zipalign'),'-c','4',apk,capture=True)
    badging=run(exe(SDK,'aapt'),'dump','badging',apk,capture=True)
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    git_commit='';git_dirty=False
    if (ROOT/'.git').exists():
        try:
            git_commit=subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip()
            git_dirty=bool(subprocess.run(['git','status','--porcelain'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip())
        except Exception:
            pass
    (DIST/(apk.name+'.sha256')).write_text(digest+'  '+apk.name+'\n',encoding='ascii')
    compatibility_alias(apk, version, 'tv')
    mobile_manifest=BUILD/'AndroidManifest.mobile.xml'
    mobile_xml=(SRC/'AndroidManifest.xml').read_text(encoding='utf-8').replace('android:value="tv"','android:value="mobile"').replace('<category android:name="android.intent.category.LEANBACK_LAUNCHER" />','')
    mobile_xml=re.sub(r'android:versionCode="[^"]+"', f'android:versionCode="{mobile_version_code}"', mobile_xml)
    mobile_xml=re.sub(r'android:versionName="[^"]+"', f'android:versionName="{mobile_version}"', mobile_xml)
    mobile_manifest.write_text(mobile_xml,encoding='utf-8')
    run(exe(SDK,'aapt2'),'link','-o',BUILD/'resources-mobile.apk','--manifest',mobile_manifest,'-I',ANDROID,'-A',aar_assets_dir,'-A',SRC/'assets','-A',catpaw_mobile_assets,'--auto-add-overlay','-0','onnx',*extra,*overlays,'-R',BUILD/'resources.zip')
    with zipfile.ZipFile(BUILD/'resources-mobile.apk') as source, zipfile.ZipFile(BUILD/'unsigned-mobile.apk','w',zipfile.ZIP_DEFLATED) as dest:
        for entry in source.infolist():dest.writestr(entry,source.read(entry.filename))
        for dex in (BUILD/'dex').glob('*.dex'):dest.write(dex,dex.name)
        for name,data in native_files_mobile.items():dest.writestr(name,data)
        for name,data in apk_resources.items():
            dest.writestr(zipfile.ZipInfo(name),data,compress_type=zipfile.ZIP_STORED)
    run(exe(SDK,'zipalign'),'-f','4',BUILD/'unsigned-mobile.apk',BUILD/'aligned-mobile.apk')
    mobile_apk=DIST/package_name(mobile_version, 'mobile')
    run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','marcomovie','--ks-pass','env:MARCOMOVIE_KEY_PASSWORD','--out',mobile_apk,BUILD/'aligned-mobile.apk')
    run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','verify','--verbose','--print-certs',mobile_apk,capture=True)
    run(exe(SDK,'zipalign'),'-c','4',mobile_apk,capture=True)
    mobile_digest=hashlib.sha256(mobile_apk.read_bytes()).hexdigest()
    (DIST/(mobile_apk.name+'.sha256')).write_text(mobile_digest+'  '+mobile_apk.name+'\n',encoding='ascii')
    compatibility_alias(mobile_apk, mobile_version, 'mobile')
    (BUILD/'apk-signature.txt').write_text(signature,encoding='utf-8')
    (BUILD/'apk-badging.txt').write_text(badging,encoding='utf-8')
    result={'apk':apk.name,'sha256':digest,'bytes':apk.stat().st_size,'mobile_apk':mobile_apk.name,'mobile_sha256':mobile_digest,'mobile_bytes':mobile_apk.stat().st_size,'signature_verified':True,'signer_sha256':signer_sha256,'zipalign_verified':True,'package':'com.pilipala.marcomovie','version':version,'version_code':version_code,'mobile_version':mobile_version,'mobile_version_code':mobile_version_code,'git_commit':git_commit,'git_dirty':git_dirty,'min_sdk':23,'target_sdk':28,'device_install_tested':False,'device_playback_tested':False,'signing':'release-verified signing key' if expected_signer else 'independent local development key; not a store release'}
    (BUILD/'build-validation.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
