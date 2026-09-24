#!/usr/bin/env python3
"""Build com.vela.demo.rpk + data.img for the vapp firmware boot test.

v2: rpk zip uses Demo/ sub-dir layout; FAT uses 4KB clusters
(mformat -c 8) to avoid the NuttX FAT cluster-chain read bug; chunked
reads in vrpk handle the rest.
"""
import os
import subprocess
import zipfile

SRC = "/home/z/dl/rpk-src/com.vela.demo"
RPK = "/home/z/dl/com.vela.demo.rpk"
IMG = "/home/z/dl/data.img"
SIZE_MB = 32

# 1. zip the rpk (manifest.json first, like aiot-toolkit does)
if os.path.exists(RPK):
    os.remove(RPK)
with zipfile.ZipFile(RPK, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(SRC, "manifest.json"), "manifest.json")
    z.write(os.path.join(SRC, "app.js"), "app.js")
    z.write(os.path.join(SRC, "Demo", "index.js"), "Demo/index.js")
print("rpk:", RPK, os.path.getsize(RPK), "bytes")

# 2. FAT image with LFN, 4KB clusters (mformat -c 8 -> 8x512B sectors)
if os.path.exists(IMG):
    os.remove(IMG)
subprocess.run(["dd", "if=/dev/zero", f"of={IMG}", "bs=1M",
                f"count={SIZE_MB}", "status=none"], check=True)
subprocess.run(["mformat", "-i", IMG, "-C",
                "-T", str(SIZE_MB * 1024 * 1024 // 512),
                "-c", "8", "-F", "-v", "VELADATA", "::"], check=True)
subprocess.run(["mmd", "-i", IMG, "::/resource"], check=False)
subprocess.run(["mmd", "-i", IMG, "::/resource/package"], check=False)
subprocess.run(["mcopy", "-i", IMG, "-n", RPK,
                "::/resource/package/com.vela.demo.rpk"], check=True)

# 3. verify listing
subprocess.run(["mdir", "-i", IMG, "-/", "::"], check=True)
print("data.img ready:", IMG, os.path.getsize(IMG), "bytes")
