/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vapp_main.c
 *
 * Open-source QuickApp (vapp) runtime for the Xiaomi Vela simulator
 * firmware. Loads real rpk packages produced by the official
 * aiot-toolkit, executes their compiled JS bundles with QuickJS and
 * renders the virtual DOM tree onto LVGL (virtio-gpu framebuffer).
 *
 * Usage:
 *   vapp hap://app/<package>          e.g. vapp hap://app/com.vela.demo
 *
 * Package resolution:
 *   1. An already-unpacked package dir <RPK_DIR>/<package>/app.js
 *   2. A package archive  <RPK_DIR>/<package>.rpk  (zip; unpacked to
 *      /data/vapps/<package>)
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.
 ****************************************************************************/

/****************************************************************************
 * Included Files
 ****************************************************************************/

#include <nuttx/config.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <uv.h>
#include <lvgl/lvgl.h>
#include <quickjs/quickjs.h>
#include <netutils/cJSON.h>
#include "vrpk.h"

#include "vhost.h"
#include "vrender.h"

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

#ifndef CONFIG_QUICKAPP_RPK_DIR
#  define CONFIG_QUICKAPP_RPK_DIR "/resource/package"
#endif

#define VAPP_DATA_DIR "/data/vapps"

/****************************************************************************
 * Private Data
 ****************************************************************************/

static char g_base[256];   /* unpacked package base dir (with trailing /) */

/****************************************************************************
 * Private Functions
 ****************************************************************************/

/****************************************************************************
 * Name: is_dir / file_exists
 ****************************************************************************/

static bool is_dir(const char *path)
{
  struct stat sb;
  return stat(path, &sb) == 0 && S_ISDIR(sb.st_mode);
}

/* Probe with open(): on NuttX FAT the stat() family can disagree with
 * the NSH/open() path resolution (LFN/case); open() is what actually
 * gets used for reading, so test with it. */

static bool file_exists(const char *path)
{
  int fd = open(path, O_RDONLY);
  if (fd >= 0)
    {
      close(fd);
      return true;
    }

  return false;
}

/****************************************************************************
 * Name: mkdirs
 ****************************************************************************/

static int mkdirs(const char *path)
{
  char tmp[256];
  char *p;
  size_t len;

  snprintf(tmp, sizeof(tmp), "%s", path);
  len = strlen(tmp);
  if (len == 0)
    {
      return -1;
    }

  if (tmp[len - 1] == '/')
    {
      tmp[len - 1] = '\0';
    }

  for (p = tmp + 1; *p != '\0'; p++)
    {
      if (*p == '/')
        {
          *p = '\0';
          mkdir(tmp, 0755);
          *p = '/';
        }
    }

  mkdir(tmp, 0755);
  return 0;
}

/****************************************************************************
 * Name: unzip_rpk
 *
 * Description:
 *   Extract a rpk (zip) archive into dest_dir using the built-in
 *   minimal zip reader (zlib inflate based).
 ****************************************************************************/

static int unzip_rpk(const char *rpk_path, const char *dest_dir)
{
  return vrpk_extract(rpk_path, dest_dir);
}

/****************************************************************************
 * Name: resolve_package
 *
 * Description:
 *   Locate (and unpack if needed) the rpk for the given package name.
 *   Fills g_base with the unpacked dir path ending in '/'.
 ****************************************************************************/

static int resolve_package(const char *pkg)
{
  char path[256];

  /* 1: already unpacked */

  snprintf(g_base, sizeof(g_base), "%s/%s/", VAPP_DATA_DIR, pkg);
  snprintf(path, sizeof(path), "%sapp.js", g_base);
  if (file_exists(path))
    {
      printf("[vapp] using unpacked package at %s\n", g_base);
      return 0;
    }

  /* 1b: unpacked directly under RPK_DIR (mounted FAT with unpacked rpk) */

  snprintf(path, sizeof(path), "%s/%s/app.js",
           CONFIG_QUICKAPP_RPK_DIR, pkg);
  if (file_exists(path))
    {
      printf("[vapp] using package dir %s/%s\n",
             CONFIG_QUICKAPP_RPK_DIR, pkg);
      snprintf(g_base, sizeof(g_base), "%s/%s/",
               CONFIG_QUICKAPP_RPK_DIR, pkg);
      return 0;
    }

  /* 2: rpk archive next to RPK_DIR */

  snprintf(path, sizeof(path), "%s/%s.rpk", CONFIG_QUICKAPP_RPK_DIR, pkg);
  if (file_exists(path))
    {
      mkdirs(g_base);
      printf("[vapp] unpacking %s into %s\n", path, g_base);
      if (unzip_rpk(path, g_base) == 0 && file_exists(
            (snprintf(path, sizeof(path), "%sapp.js", g_base), path)))
        {
          return 0;
        }

      fprintf(stderr, "[vapp] rpk unpack failed\n");
      return -1;
    }

  /* 2b: also try the raw rpk without .rpk extension (zip) */

  snprintf(path, sizeof(path), "%s/%s.zip", CONFIG_QUICKAPP_RPK_DIR, pkg);
  if (file_exists(path))
    {
      mkdirs(g_base);
      printf("[vapp] unpacking %s into %s\n", path, g_base);
      if (unzip_rpk(path, g_base) == 0 && file_exists(
            (snprintf(path, sizeof(path), "%sapp.js", g_base), path)))
        {
          return 0;
        }

      fprintf(stderr, "[vapp] rpk unpack failed\n");
      return -1;
    }

  fprintf(stderr, "[vapp] package not found: %s (looked in %s)\n",
          pkg, CONFIG_QUICKAPP_RPK_DIR);
  return -1;
}

/****************************************************************************
 * Name: read_file
 *
 * Description:
 *   Read a whole file into a NUL-terminated malloc'ed buffer.
 *   Returns NULL on failure. Caller frees.
 ****************************************************************************/

static char *read_file(const char *path, size_t *outlen)
{
  FILE *f = fopen(path, "r");
  char *buf;
  long size;

  if (f == NULL)
    {
      return NULL;
    }

  fseek(f, 0, SEEK_END);
  size = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (size < 0)
    {
      fclose(f);
      return NULL;
    }

  buf = malloc((size_t)size + 1);
  if (buf == NULL)
    {
      fclose(f);
      return NULL;
    }

  if (fread(buf, 1, (size_t)size, f) != (size_t)size)
    {
      free(buf);
      fclose(f);
      return NULL;
    }

  fclose(f);
  buf[size] = '\0';
  if (outlen != NULL)
    {
      *outlen = (size_t)size;
    }

  return buf;
}

/****************************************************************************
 * Name: js_module_loader / js_module_normalize
 *
 * Description:
 *   QuickJS module resolution against the unpacked package directory.
 *   Module names are absolute or base-relative file paths.
 ****************************************************************************/

static char *js_module_normalize(JSContext *ctx, const char *base_name,
                                 const char *name, void *opaque)
{
  char *out;

  (void)ctx;
  (void)base_name;
  (void)opaque;

  if (name[0] == '/')
    {
      out = strdup(name);
    }
  else
    {
      size_t n = strlen(g_base) + strlen(name) + 1;
      out = malloc(n);
      if (out != NULL)
        {
          snprintf(out, n, "%s%s", g_base, name);
        }
    }

  return out;
}

static JSModuleDef *vapp_module_loader(JSContext *ctx, const char *name,
                                     void *opaque)
{
  size_t len = 0;
  char *buf = read_file(name, &len);
  JSValue func_val;
  JSModuleDef *m;

  (void)opaque;
  if (buf == NULL)
    {
      JS_ThrowReferenceError(ctx, "could not load module '%s'", name);
      return NULL;
    }

  /* Compile the module (lazy evaluation) */

  func_val = JS_Eval(ctx, buf, len, name,
                     JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
  free(buf);
  if (JS_IsException(func_val))
    {
      return NULL;
    }

  /* Note: js_module_set_import_meta() (quickjs-libc) is unavailable in
   * the MINI/NONE interpreter build; import.meta is not used by rpk
   * bundles, so we skip it and take the module pointer directly. */

  m = (JSModuleDef *)JS_VALUE_GET_PTR(func_val);
  JS_FreeValue(ctx, func_val);
  return m;
}

/****************************************************************************
 * Name: call_entry_lifecycle
 *
 * Description:
 *   Helper: call obj.<fn>() if present.
 ****************************************************************************/

static void call_lifecycle(JSContext *ctx, JSValue obj, const char *fn)
{
  JSValue func = JS_GetPropertyStr(ctx, obj, fn);
  JSValue ret;

  if (JS_IsFunction(ctx, func))
    {
      ret = JS_Call(ctx, func, obj, 0, NULL);
      if (JS_IsException(ret))
        {
          const char *s;
          JSValue exc = JS_GetException(ctx);
          s = JS_ToCString(ctx, exc);
          fprintf(stderr, "[vapp] %s() failed: %s\n", fn, s ? s : "?");
          if (s)
            {
              JS_FreeCString(ctx, s);
            }

          JS_FreeValue(ctx, exc);
        }

      JS_FreeValue(ctx, ret);
    }

  JS_FreeValue(ctx, func);
}

/****************************************************************************
 * Name: run_boot_script
 *
 * Description:
 *   Execute the page bootstrap: entry() -> template/style attach,
 *   onInit(), template(vm) -> globalThis.__render_tree (JSON string).
 ****************************************************************************/

static int run_boot_script(JSContext *ctx)
{
  static const char script[] =
    "(function () {"
    "  var page = globalThis.__page_exps;"
    "  if (typeof page.entry === 'function') { page.entry(page); }"
    "  var vm = page.default;"
    "  globalThis.__page_vm = vm;"
    "  globalThis.__page_style = vm.style || [];"
    "  if (typeof vm.onInit === 'function') { vm.onInit.call(vm); }"
    "  var tree = vm.template(vm);"
    "  var styleMap = {};"
    "  var styles = globalThis.__page_style || [];"
    "  styles.forEach(function (entry) {"
    "    var sels = entry[0]; var obj = entry[1];"
    "    var key = null;"
    "    sels.forEach(function (s) {"
    "      if (s[0] === 0) { key = s[1]; }"
    "      else if (!key) { key = s[1]; }"
    "    });"
    "    if (key) {"
    "      var tgt = styleMap[key] || (styleMap[key] = {});"
    "      Object.keys(obj).forEach(function (k) { tgt[k] = obj[k]; });"
    "    }"
    "  });"
    "  function styleOf(node) {"
    "    var out = {};"
    "    var o = node.__props.__opts__ || {};"
    "    (o.classList || []).forEach(function (c) {"
    "      var s = styleMap[c];"
    "      if (s) {"
    "        Object.keys(s).forEach(function (k) { out[k] = s[k]; });"
    "      }"
    "    });"
    "    if (o.style) {"
    "      Object.keys(o.style).forEach(function (k) {"
    "        out[k] = o.style[k];"
    "      });"
    "    }"
    "    return out;"
    "  }"
    "  function conv(node) {"
    "    var o = node.__props.__opts__ || {};"
    "    var r = { tag: node.__tag,"
    "              class: (o.classList || []).join(' '),"
    "              style: styleOf(node),"
    "              children: [] };"
    "    if (o.value !== undefined) { r.value = String(o.value); }"
    "    if (o.src !== undefined) { r.src = String(o.src); }"
    "    (node.__children || []).forEach(function (ch) {"
    "      r.children.push(conv(ch));"
    "    });"
    "    return r;"
    "  }"
    "  globalThis.__render_tree = JSON.stringify(conv(tree));"
    "})();";

  JSValue ret = JS_Eval(ctx, script, strlen(script), "<vboot>",
                        JS_EVAL_TYPE_GLOBAL);
  int status = 0;

  if (JS_IsException(ret))
    {
      JSValue exc = JS_GetException(ctx);
      const char *s = JS_ToCString(ctx, exc);
      fprintf(stderr, "[vapp] boot script failed: %s\n", s ? s : "?");
      if (s)
        {
          JS_FreeCString(ctx, s);
        }

      JS_FreeValue(ctx, exc);
      status = -1;
    }

  JS_FreeValue(ctx, ret);
  return status;
}

/****************************************************************************
 * Name: render_page
 *
 * Description:
 *   Fetch globalThis.__render_tree and map it onto the LVGL screen.
 ****************************************************************************/

static int render_page(lv_obj_t *root, JSContext *ctx)
{
  JSValue g = JS_GetGlobalObject(ctx);
  JSValue jtree = JS_GetPropertyStr(ctx, g, "__render_tree");
  const char *json = JS_ToCString(ctx, jtree);
  cJSON *tree;
  int count;

  if (json == NULL)
    {
      fprintf(stderr, "[vapp] no render tree produced\n");
      JS_FreeValue(ctx, g);
      JS_FreeValue(ctx, jtree);
      return -1;
    }

  tree = cJSON_Parse(json);
  if (tree == NULL)
    {
      fprintf(stderr, "[vapp] render tree JSON parse failed\n");
      JS_FreeCString(ctx, json);
      JS_FreeValue(ctx, jtree);
      JS_FreeValue(ctx, g);
      return -1;
    }

  count = vrender_tree(root, tree);
  printf("[vapp] rendered %d widget(s)\n", count > 0 ? count - 1 : 0);

  cJSON_Delete(tree);
  JS_FreeCString(ctx, json);
  JS_FreeValue(ctx, jtree);
  JS_FreeValue(ctx, g);
  return 0;
}

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int main(int argc, char *argv[])
{
  const char *uri;
  const char *pkg;
  char path[256];
  char appjs[300];
  char pagejs[300];
  char *manifest_buf;
  cJSON *manifest;
  cJSON *router;
  cJSON *entry;
  cJSON *pages;
  cJSON *page_def;
  const char *entry_key = NULL;
  const char *component = "index";
  const char *page_path;
  JSRuntime *rt;
  JSContext *ctx;
  JSValue exps;
  uv_loop_t loop;
  lv_nuttx_dsc_t info;
  lv_nuttx_result_t result;
  lv_nuttx_uv_t uv_info;
  void *lv_uv_data;
  int status = 0;

  if (argc < 2)
    {
      printf("usage: %s hap://app/<package>\n", argv[0]);
      return 0;
    }

  uri = argv[1];
  if (strncmp(uri, "hap://app/", 10) == 0)
    {
      pkg = uri + 10;
    }
  else if (strncmp(uri, "hap://", 6) == 0)
    {
      /* hap://<pkg> or hap://apps/<pkg> tolerated */

      pkg = uri + 6;
      if (strncmp(pkg, "apps/", 5) == 0)
        {
          pkg += 5;
        }
    }
  else
    {
      /* bare package name */

      pkg = uri;
    }

  printf("[vapp] open runtime starting for %s\n", pkg);

  /* Resolve the package (unpack rpk when needed) */

  if (resolve_package(pkg) != 0)
    {
      return 1;
    }

  /* Parse manifest.json for the router */

  snprintf(path, sizeof(path), "%smanifest.json", g_base);
  manifest_buf = read_file(path, NULL);
  if (manifest_buf == NULL)
    {
      fprintf(stderr, "[vapp] missing manifest.json in %s\n", g_base);
      return 1;
    }

  manifest = cJSON_Parse(manifest_buf);
  free(manifest_buf);
  if (manifest == NULL)
    {
      fprintf(stderr, "[vapp] manifest.json parse failed\n");
      return 1;
    }

  router = cJSON_GetObjectItemCaseSensitive(manifest, "router");
  entry = cJSON_GetObjectItemCaseSensitive(router, "entry");
  if (cJSON_IsString(entry) && entry->valuestring != NULL)
    {
      entry_key = entry->valuestring;
      if (entry_key[0] == '/')
        {
          entry_key++;
        }
    }

  if (entry_key == NULL)
    {
      fprintf(stderr, "[vapp] manifest router.entry missing\n");
      cJSON_Delete(manifest);
      return 1;
    }

  pages = cJSON_GetObjectItemCaseSensitive(router, "pages");
  page_def = cJSON_GetObjectItemCaseSensitive(pages, entry_key);
  if (page_def != NULL)
    {
      cJSON *comp = cJSON_GetObjectItemCaseSensitive(page_def,
                                                     "component");
      if (cJSON_IsString(comp) && comp->valuestring != NULL)
        {
          component = comp->valuestring;
        }
    }

  page_path = entry_key;
  snprintf(pagejs, sizeof(pagejs), "%s%s/%s.js", g_base, page_path,
           component);

  if (!file_exists(pagejs))
    {
      fprintf(stderr, "[vapp] entry page missing: %s\n", pagejs);
      cJSON_Delete(manifest);
      return 1;
    }

  /* entry_key points into the manifest: print BEFORE freeing it */
  printf("[vapp] entry page: %s (%s)\n", entry_key, pagejs);
  cJSON_Delete(manifest);

  /* LVGL + display (virtio-gpu via NuttX framebuffer) */

  lv_init();
  lv_nuttx_dsc_init(&info);
  lv_nuttx_init(&info, &result);
  if (result.disp == NULL)
    {
      fprintf(stderr, "[vapp] display init failed\n");
      return 1;
    }

  /* QuickJS bootstrap */

  rt = JS_NewRuntime();
  if (rt == NULL)
    {
      fprintf(stderr, "[vapp] JS_NewRuntime failed\n");
      return 1;
    }

  JS_SetModuleLoaderFunc(rt, js_module_normalize, vapp_module_loader, NULL);
  ctx = JS_NewContext(rt);
  if (ctx == NULL)
    {
      fprintf(stderr, "[vapp] JS_NewContext failed\n");
      JS_FreeRuntime(rt);
      return 1;
    }

  /* libuv loop (drives LVGL refresh + JS timers) */

  uv_loop_init(&loop);
  vhost_init(ctx, &loop);

  /* Import app + page modules */

  snprintf(appjs, sizeof(appjs), "%sapp.js", g_base);
  if (vhost_eval_boot(ctx, appjs, pagejs) != 0)
    {
      status = 1;
      goto out;
    }

  /* App lifecycle */

  exps = JS_NewObject(ctx);
  if (vhost_call_factory(ctx, "__app_factory", exps) != 0)
    {
      JS_FreeValue(ctx, exps);
      status = 1;
      goto out;
    }

  call_lifecycle(ctx, exps, "onCreate");

  /* Page lifecycle + render */

  exps = JS_NewObject(ctx);
  JS_SetPropertyStr(ctx, exps, "entry", JS_UNDEFINED);
  if (vhost_call_factory(ctx, "__page_factory", exps) != 0)
    {
      JS_FreeValue(ctx, exps);
      status = 1;
      goto out;
    }

  {
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__page_exps", JS_DupValue(ctx, exps));
    JS_FreeValue(ctx, g);
  }

  if (run_boot_script(ctx) != 0)
    {
      JS_FreeValue(ctx, exps);
      status = 1;
      goto out;
    }

  if (render_page(lv_scr_act(), ctx) != 0)
    {
      status = 1;
      goto out;
    }

  /* Run: LVGL refresh via libuv integration + JS timers.
   * Force the first frame out (single-buffer: no pan means the
   * driver never sees a flush request), and keep the loop alive
   * with a heartbeat timer - uv_run returns as soon as no active
   * handles remain, which would end the app before any timer
   * driven rendering happens. */

  lv_obj_invalidate(lv_scr_act());
  lv_refr_now(NULL);

  lv_memset(&uv_info, 0, sizeof(uv_info));
  uv_info.loop = &loop;
  uv_info.disp = result.disp;
  uv_info.indev = result.indev;
  uv_info.uindev = result.utouch_indev;
  lv_uv_data = lv_nuttx_uv_init(&uv_info);

  {
    static uv_timer_t ka_timer;

    uv_timer_init(&loop, &ka_timer);
    uv_timer_start(&ka_timer, NULL, 0, 200); /* 5 Hz keep-alive */
  }

  uv_run(&loop, UV_RUN_DEFAULT);
  lv_nuttx_uv_deinit(&lv_uv_data);

out:
  uv_loop_close(&loop);
  JS_FreeContext(ctx);
  JS_FreeRuntime(rt);
  printf("[vapp] exited (%d)\n", status);
  return status;
}
