#!/bin/bash
# Rebuild openvela source tree for vapp firmware (Phase 1 last piece)
# Target: qemu-armv7a (in-tree board, -M virt) + LVGL + virtio-gpu/blk + QUICKAPP_VAPP
mkdir -p /home/z/ov || exit 1
cd /home/z/ov || exit 1

BR=dev-ai-contest-2026

clone() { # clone <repo> <path> [branch]
  local repo=$1 path=$2 br=${3:-$BR}
  if [ -d "$path/.git" ]; then
    (cd "$path" && git fetch --depth 1 origin "$br" -q && git checkout -q FETCH_HEAD && echo "switched $path -> $br") || return 1
  else
    mkdir -p "$(dirname "$path")"
    git clone --depth 1 -q -b "$br" "https://github.com/open-vela/$repo" "$path" && echo "cloned $path" || return 1
  fi
}

# frameworks subrepos already cloned: switch branch
clone frameworks_runtimes frameworks/runtimes
clone frameworks_runtimes_quickapp frameworks/runtimes/quickapp
clone frameworks_runtimes_services frameworks/runtimes/services
clone frameworks_runtimes_services_am frameworks/runtimes/services/am
clone frameworks_runtimes_services_pm frameworks/runtimes/services/pm
clone frameworks_runtimes_services_wm frameworks/runtimes/services/wm
clone frameworks_runtimes_services_system_server frameworks/runtimes/services/system_server
clone frameworks_system frameworks/system
clone frameworks_system_utils frameworks/system/utils
clone frameworks_system_utils_uv frameworks/system/utils/uv

# new frameworks subrepos (deps of QUICKAPP/UIKIT)
clone frameworks_graphics frameworks/graphics
clone frameworks_graphics_uikit frameworks/graphics/uikit
clone frameworks_runtimes_feature frameworks/runtimes/feature
clone frameworks_runtimes_ash frameworks/runtimes/ash

# main repos branch switch
clone nuttx nuttx
clone nuttx-apps apps
clone build build

# external deps (from manifest paths)
clone external_yoga external/yoga
clone external_protobuf-c external/protobuf-c
clone external_curl external/curl
clone external_libpng external/libpng
clone external_freetype external/freetype/freetype
clone external_freetype_subprojects_dlg external/freetype/freetype/subprojects/dlg || echo "(dlg optional, skip)"
clone external_nanopb external/nanopb
clone external_rapidjson external/rapidjson

# apps sub-glue repos
clone apps_graphics_lvgl apps/graphics/lvgl/lvgl
clone apps_system_libuv apps/system/libuv/libuv
clone apps_interpreters_quickjs apps/interpreters/quickjs/quickjs

echo "ALL DONE"
df -h / | tail -1
