"""Offline keyboard and bounded-search behavior checks; no TV or real content request."""
from pathlib import Path
import os, subprocess
ROOT=Path(__file__).resolve().parents[2]
JDK=Path(os.environ.get('MARCOMOVIE_JDK',os.environ.get('JAVA_HOME', 'missing-jdk')))
out=ROOT/'android/tests/global-search-classes';out.mkdir(exist_ok=True)
jar=ROOT/'android/tests/lib/json-20240303.jar'
sources=[ROOT/'android/src/main/java/com/pilipala/marcomovie'/name for name in ('Cms.java','RequestScope.java','SourceEngine.java','HttpT4Engine.java','PinyinKeyboardModel.java','SearchCoordinator.java','SearchVariants.java','Safety.java','AppLog.java')]
# Cms.listing 出口挂了 Safety（NSFW 过滤），safety 包引用 android.content.*：
# 编译期需要 android.jar，JVM 运行不触发 Android 框架。
sources+=sorted((ROOT/'android/src/main/java/com/pilipala/marcomovie/safety').glob('*.java'))
ANDROID_JAR=Path(os.environ.get('MARCOMOVIE_ANDROID_JAR',str(ROOT/'tools/android-35/android.jar')))
if not ANDROID_JAR.exists():
    import sys
    sys.path.insert(0,str(ROOT/'scripts'))
    from prepare_android_platform import prepare
    prepare()
subprocess.run([str(JDK/'bin'/('javac.exe' if os.name=='nt' else 'javac')),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-cp',str(jar)+os.pathsep+str(ANDROID_JAR),'-d',str(out),str(ROOT/'shared/java/com/pilipala/source/Warehouse.java'),*map(str,sources),str(ROOT/'android/tests/GlobalSearchChecks.java'),str(ROOT/'android/tests/SearchVariantsChecks.java')],check=True)
result=subprocess.run([str(JDK/'bin'/('java.exe' if os.name=='nt' else 'java')),'-cp',str(out)+os.pathsep+str(jar),'com.pilipala.marcomovie.GlobalSearchChecks',str(ROOT)],check=True,text=True,encoding='utf8',capture_output=True)
print(result.stdout);(ROOT/'android/tests/global-search-results.txt').write_text(result.stdout,encoding='utf8')

subprocess.run([str(JDK/'bin'/('java.exe' if os.name=='nt' else 'java')),'-cp',str(out)+os.pathsep+str(jar),'com.pilipala.marcomovie.SearchVariantsChecks'],check=True)
