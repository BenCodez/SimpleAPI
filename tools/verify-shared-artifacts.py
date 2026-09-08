#!/usr/bin/env python3
"""Check packaged class boundaries and compile/run a downstream native consumer."""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
MODULES=("simpleapi-core", "simpleapi-configurate", "simpleapi-sql")
FORBIDDEN=(b"org/bukkit/", b"org/spigotmc/", b"io/papermc/paper/", b"net/minecraft/",
           b"net/fabricmc/", b"net/minecraftforge/", b"net/neoforged/", b"net/md_5/bungee/",
           b"com/velocitypowered/api/")

def main():
    jars=[]
    for module in MODULES:
        target=ROOT/module/"target"
        candidates=[p for p in target.glob(f"{module}-*.jar") if not p.name.endswith(("-sources.jar","-javadoc.jar"))]
        if len(candidates)!=1: raise RuntimeError(f"Expected one freshly built artifact for {module}")
        jar=candidates[0]
        with zipfile.ZipFile(jar) as archive:
            classes=[n for n in archive.namelist() if n.endswith(".class")]
            if not classes: raise RuntimeError(f"Empty artifact: {jar}")
            for name in classes:
                data=archive.read(name)
                if any(prefix in data or prefix.decode() in name for prefix in FORBIDDEN):
                    raise RuntimeError(f"Platform dependency in {module}: {name}")
        sources=jar.with_name(jar.stem+"-sources.jar")
        if not sources.is_file(): raise RuntimeError(f"Missing source artifact: {sources}")
        # Copied at build time from the existing implementation, never maintained twice.
        for source in (target/"generated-sources/shared").rglob("*.java"):
            relative=source.relative_to(target/"generated-sources/shared")
            if source.read_bytes()!=(ROOT/"SimpleAPI/src/main/java"/relative).read_bytes():
                raise RuntimeError(f"Shared source drift: {relative}")
        jars.append(jar)
        jars.extend(sorted((target/"runtime-deps").glob("*.jar")))
    jars=list(dict.fromkeys(p.resolve() for p in jars))
    published_classes={}
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith("com/bencodez/simpleapi/") and name.endswith(".class"):
                    data=archive.read(name)
                    if name in published_classes and published_classes[name]!=data:
                        raise RuntimeError(f"Conflicting shared class bytes: {name} in {jar}")
                    published_classes[name]=data
                if any(name.startswith(prefix.decode()) for prefix in FORBIDDEN):
                    raise RuntimeError(f"Platform classes in native runtime dependency: {jar.name}: {name}")
    subprocess.run(["java",str(ROOT/"tools/SharedArtifactProbe.java"),*[str(p) for p in jars]],check=True)
    classpath=os.pathsep.join(str(p) for p in jars)
    with tempfile.TemporaryDirectory(prefix="simpleapi-consumer-") as output:
        subprocess.run(["javac","--release","21","-proc:none","-cp",classpath,"-d",output,str(ROOT/"tools/NativeConfigSmoke.java")],check=True)
        subprocess.run(["java","-cp",output+os.pathsep+classpath,"NativeConfigSmoke"],check=True)
        legacy_cp_file=ROOT/"SimpleAPI/target/compatibility-classpath.txt"
        legacy_cp=legacy_cp_file.read_text().strip()
        parity_cp=classpath+os.pathsep+str(ROOT/"SimpleAPI/target/SimpleAPI.jar")+os.pathsep+legacy_cp
        subprocess.run(["javac","--release","21","-proc:none","-cp",parity_cp,"-d",output,str(ROOT/"tools/ConfigParityProbe.java")],check=True)
        subprocess.run(["java","-cp",output+os.pathsep+parity_cp,"ConfigParityProbe"],check=True)
    print("Shared binary/source artifacts and runtime boundaries passed")

if __name__=="__main__": main()
