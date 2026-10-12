"""Builds the three Vantage Server release zips (Windows x64).

    python tools/release.py <version> <out dir>

Each edition is its own binary (MCRS_EDITION at build time). The zips also carry bundled
performance mods in run/mods, downloaded from Modrinth at pinned versions and checked by
SHA-512, with their licenses in licenses/:
  all editions:           FerriteCore (MIT): less memory, tick-exact with Vantage Vanilla
  balanced, performance:  Lithium (LGPL-3.0): faster villagers (POI, brain), hoppers and
                          block entities; not tick-exact in our harness, so not in vanilla
                          ScalableLux (LGPL-3.0): Starlight light engine, small light
                          differences where vanilla's lighting is buggy
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINECRAFT = "26.3"
EDITIONS = ["vanilla", "balanced", "performance"]
# Modrinth project, version number, editions, license, license URL.
MODS = [
    ("ferrite-core", "9.0.0-fabric", EDITIONS, "MIT", "https://raw.githubusercontent.com/malte0811/FerriteCore/main/LICENSE"),
    ("lithium", "mc26.3-0.26.2-fabric", ["balanced", "performance"], "LGPL-3.0-only",
     "https://raw.githubusercontent.com/CaffeineMC/lithium/develop/LICENSE.md"),
    ("scalablelux", "0.3.0-alpha.0.6+26.3", ["balanced", "performance"], "LGPL-3.0-only",
     "https://raw.githubusercontent.com/RelativityMC/ScalableLux/master/LICENSE"),
]

README = {
    "vanilla": """Vantage Server {v} Vanilla - Minecraft 26.3 (Fabric 0.19.5), Windows x64

Plays exactly like vanilla. Hot parts of the game run as exact, faster code in Rust and Java;
the world is generated block for block like vanilla. Fabric mods work as usual.
Bundled in run/mods: FerriteCore (less memory, no gameplay change).
""",
    "balanced": """Vantage Server {v} Balanced - Minecraft 26.3 (Fabric 0.19.5), Windows x64

Vanilla with Rust parts plus an activation range for entities: mobs with no player within
48 blocks (monsters), 24 (water mobs, bats) or 32 (others) do a full tick only every 4th tick.
Mobs that fight, are hurt, ride, are leashed, breed or raid, and bosses always tick. World
generation is exactly vanilla.
Bundled in run/mods: FerriteCore (less memory), Lithium (faster villagers, hoppers and block
entities) and ScalableLux (Starlight light engine; light can differ slightly where vanilla's
lighting is buggy).
""",
    "performance": """Vantage Server {v} Performance - Minecraft 26.3 (Fabric 0.19.5), Windows x64

Rust parts plus Paper/Pufferfish/ServerCore-like changes and Pumpkin-like terrain. NOT vanilla:
- stronger activation range (mobs and resting items far from players), brain throttle
- fewer spawn attempts (same caps), collision limit 8, idle hoppers wait 4 ticks
- Mojang's experimental redstone without the data pack
- dynamic simulation/view distance by tick time, villager lobotomy for boxed-in villagers,
  breeding cap (32 per kind within 64 blocks), larger item/xp merge radius
- approximate terrain like Pumpkin (biomes and structures follow the seed exactly)
Bundled in run/mods: FerriteCore, Lithium and ScalableLux.
""",
}

SETUP = """
1. Install a JDK 25 (e.g. Eclipse Temurin 25 from https://adoptium.net) or unpack one into a
   folder named ".tools" next to mcrs.exe, or set java_home in mcrs.toml.
2. Run mcrs.exe once from this folder. It downloads Minecraft and Fabric into run/ and stops.
3. Accept the Minecraft EULA: set eula=true in run/eula.txt.
4. Run mcrs.exe again. Server settings are in run/server.properties, mods go into run/mods/.

Every change can be switched off with a JVM option in jvm_options (mcrs.toml), e.g.
"-Dmcrs.worldgen=false"; see the README on GitHub. Licenses of bundled mods: licenses/.
"""

TOML = """minecraft_version = "26.3"
fabric_loader_version = "0.19.5"
fabric_installer_version = "1.1.2"
run_dir = "run"
max_memory = "4G"
jvm_options = ["-XX:+UseZGC"]
server_args = ["nogui"]
tick_report_interval_secs = 30
"""


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "vantage-server-release"})) as r:
        return r.read()


def mod_file(project, version):
    versions = json.loads(fetch(f"https://api.modrinth.com/v2/project/{project}/version"))
    for v in versions:
        if v["version_number"] == version:
            f = next((f for f in v["files"] if f["primary"]), v["files"][0])
            data = fetch(f["url"])
            if hashlib.sha512(data).hexdigest() != f["hashes"]["sha512"]:
                raise SystemExit(f"{project} {version}: checksum mismatch")
            return f["filename"], data
    raise SystemExit(f"{project} {version} not found on Modrinth")


def main():
    version, out = sys.argv[1], os.path.abspath(sys.argv[2])
    os.makedirs(out, exist_ok=True)
    env = dict(os.environ)
    jdk = os.path.join(ROOT, ".tools", "jdk-25.0.4.1+1")
    subprocess.run(["sh", "tools/build-bridge.sh"], cwd=ROOT, check=True)
    jars = [f for f in os.listdir(os.path.join(ROOT, "bridge-mod/build/libs")) if f.startswith("mcrs-bridge-") and "sources" not in f]
    jar = max(jars, key=lambda f: os.path.getmtime(os.path.join(ROOT, "bridge-mod/build/libs", f)))
    mods = {project: mod_file(project, v) for project, v, _, _, _ in MODS}
    licenses = {project: fetch(url) for project, _, _, _, url in MODS}
    # The LGPL builds on the GPL, whose text has to come along too.
    gpl = fetch("https://www.gnu.org/licenses/gpl-3.0.txt")
    for edition in EDITIONS:
        env["MCRS_EDITION"] = edition
        subprocess.run(["cargo", "build", "--release", "-p", "mcrs"], cwd=ROOT, env=env, check=True)
        base = os.path.join(out, edition, "vantage-server")
        shutil.rmtree(os.path.join(out, edition), ignore_errors=True)
        os.makedirs(os.path.join(base, "bridge-mod/build/libs"))
        os.makedirs(os.path.join(base, "run/mods"))
        os.makedirs(os.path.join(base, "licenses"))
        shutil.copy(os.path.join(ROOT, "target/release/mcrs.exe"), base)
        shutil.copy(os.path.join(ROOT, "bridge-mod/build/libs", jar), os.path.join(base, "bridge-mod/build/libs"))
        with open(os.path.join(base, "mcrs.toml"), "w", newline="\n") as f:
            f.write(TOML)
        with open(os.path.join(base, "README.txt"), "w", newline="\n") as f:
            f.write(README[edition].format(v=version) + SETUP)
        notice = []
        for project, v, editions, license_id, _ in MODS:
            if edition not in editions:
                continue
            name, data = mods[project]
            with open(os.path.join(base, "run/mods", name), "wb") as f:
                f.write(data)
            with open(os.path.join(base, "licenses", f"{project}-LICENSE.txt"), "wb") as f:
                f.write(licenses[project])
            if license_id.startswith("LGPL"):
                with open(os.path.join(base, "licenses", "GPL-3.0.txt"), "wb") as f:
                    f.write(gpl)
            notice.append(f"{name}: {project} {v}, {license_id}, unmodified, from https://modrinth.com/mod/{project}")
        with open(os.path.join(base, "licenses", "THIRD-PARTY.txt"), "w", newline="\n") as f:
            f.write("Bundled mods (unmodified; source code at their Modrinth pages):\n" + "\n".join(notice) + "\n")
        zip_path = os.path.join(out, f"vantage-server-{version}-{edition}-windows-x64.zip")
        if os.path.exists(zip_path):
            os.remove(zip_path)
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
            for d, _, files in os.walk(base):
                for name in files:
                    p = os.path.join(d, name)
                    z.write(p, os.path.relpath(p, os.path.join(out, edition)).replace(os.sep, "/"))
        print(zip_path, os.path.getsize(zip_path))
    # Leave the default (vanilla) binary in target/ for development.
    env.pop("MCRS_EDITION")
    subprocess.run(["cargo", "build", "--release", "-p", "mcrs"], cwd=ROOT, env=env, check=True)


if __name__ == "__main__":
    main()
