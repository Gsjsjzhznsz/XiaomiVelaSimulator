#!/usr/bin/env python3
"""v2.2.13: 发布前清单校验 —— 杜绝「资产换了、清单没跟」的发布事故。

v2.2.12 真机事故复盘：发布提交更换了 assets/images/nuttx.bin（v2.2.12 固件，
3503680B, sha c3751875…），但 image_manifest.json 仍停留在 v2.2.11 条目
（3487280B, sha c839ae6b…）。App 侧 ensureAssetsCurrent 按「部署文件 sha ==
清单 sha」判定一致性 → 升级用户设备上的旧固件碰巧与陈旧清单匹配 →
新固件永不部署，触摸/布局修复长期未生效，且日志无任何异常痕迹。

本脚本在 CI 构建前重算全部 asset:// 资产的实际 sha256 与 size，与清单逐项
比对，不一致立即失败（fail fast），让这类事故在流水线上而非用户手机上暴露。
"""
import hashlib
import json
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets"
MANIFEST = ASSETS / "image_manifest.json"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(128 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if not MANIFEST.is_file():
        print(f"[verify-manifest] FAIL: 清单不存在: {MANIFEST}")
        return 1
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    errors = []
    checked = 0
    for entry in manifest.get("images", []):
        for f in entry.get("files", []):
            url = f.get("url", "")
            if not url.startswith("asset://"):
                continue  # 远程 URL 资产不在本地，跳过（其正确性由下载校验兜底）
            asset = ASSETS / url.removeprefix("asset://")
            label = f"{entry.get('id', '?')}:{f.get('out', '?')}"
            if not asset.is_file():
                errors.append(f"{label}: 资产缺失 {asset}")
                continue
            checked += 1
            actual_sha = sha256_of(asset)
            actual_size = asset.stat().st_size
            want_sha = f.get("sha256", "")
            want_size = f.get("size", 0)
            if want_sha and actual_sha.lower() != want_sha.lower():
                errors.append(
                    f"{label}: sha256 不一致 清单={want_sha[:16]}… 实际={actual_sha[:16]}…"
                )
            if want_size and actual_size != want_size:
                errors.append(f"{label}: size 不一致 清单={want_size} 实际={actual_size}")
    if errors:
        print("[verify-manifest] FAIL —— 清单与 APK 内置资产不一致（发布前必须更新清单）:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"[verify-manifest] OK —— {checked} 个内置资产 sha256/size 与清单一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
