/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vhost.c
 *
 * Open-source QuickApp runtime host for the Vela simulator firmware.
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

#include <uv.h>
#include <quickjs/quickjs.h>

#include "vhost.h"

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

#define VHOST_MAX_TIMERS 64

/****************************************************************************
 * Private Types
 ****************************************************************************/

struct vhost_timer_s
{
  JSContext *ctx;
  JSValue func;
  uv_timer_t handle;
  bool used;
};

/****************************************************************************
 * Private Data
 ****************************************************************************/

static uv_loop_t *g_uvloop;
static struct vhost_timer_s g_timers[VHOST_MAX_TIMERS];

/****************************************************************************
 * Private Function Prototypes
 ****************************************************************************/

static JSValue vhost_set_timer(JSContext *ctx, JSValueConst this_val,
                               int argc, JSValueConst *argv, bool repeat);

/****************************************************************************
 * Name: vhost_throw
 ****************************************************************************/

static void vhost_throw(JSContext *ctx, const char *where)
{
  JSValue exc = JS_GetException(ctx);
  const char *msg = JS_ToCString(ctx, exc);
  fprintf(stderr, "[vapp] JS exception in %s: %s\n", where,
          msg ? msg : "?");
  if (msg)
    {
      JS_FreeCString(ctx, msg);
    }

  JS_FreeValue(ctx, exc);
}

/****************************************************************************
 * Name: vhost_console_log
 ****************************************************************************/

static JSValue vhost_console_log(JSContext *ctx, JSValueConst this_val,
                                 int argc, JSValueConst *argv)
{
  int i;

  for (i = 0; i < argc; i++)
    {
      const char *s = JS_ToCString(ctx, argv[i]);
      if (i > 0)
        {
          printf(" ");
        }

      printf("%s", s ? s : "");
      if (s)
        {
          JS_FreeCString(ctx, s);
        }
    }

  printf("\n");
  fflush(stdout);
  return JS_UNDEFINED;
}

/****************************************************************************
 * Name: vhost_timer_cb
 ****************************************************************************/

static void vhost_timer_cb(uv_timer_t *handle)
{
  struct vhost_timer_s *t = handle->data;
  JSValue ret;

  if (t == NULL || !t->used)
    {
      return;
    }

  ret = JS_Call(t->ctx, t->func, JS_UNDEFINED, 0, NULL);
  if (JS_IsException(ret))
    {
      vhost_throw(t->ctx, "timer");
    }

  JS_FreeValue(t->ctx, ret);
}

/****************************************************************************
 * Name: vhost_set_timer
 *
 * Description:
 *   Shared implementation of setTimeout()/setInterval().
 ****************************************************************************/

static JSValue vhost_set_timer(JSContext *ctx, JSValueConst this_val,
                               int argc, JSValueConst *argv, bool repeat)
{
  int64_t delay_ms = 0;
  int i;
  struct vhost_timer_s *t = NULL;
  uint64_t uvms;

  if (argc < 1 || !JS_IsFunction(ctx, argv[0]))
    {
      return JS_UNDEFINED;
    }

  if (argc >= 2)
    {
      JS_ToInt64(ctx, &delay_ms, argv[1]);
    }

  for (i = 0; i < VHOST_MAX_TIMERS; i++)
    {
      if (!g_timers[i].used)
        {
          t = &g_timers[i];
          break;
        }
    }

  if (t == NULL)
    {
      fprintf(stderr, "[vapp] timer table full\n");
      return JS_UNDEFINED;
    }

  t->ctx = ctx;
  t->func = JS_DupValue(ctx, argv[0]);
  t->used = true;
  t->handle.data = t;

  uv_timer_init(g_uvloop, &t->handle);
  uvms = delay_ms < 1 ? 1 : (uint64_t)delay_ms;
  uv_timer_start(&t->handle, vhost_timer_cb, uvms, repeat ? uvms : 0);

  /* Timer id = slot index + 1, 0 is reserved as "no timer" */

  return JS_NewInt32(ctx, i + 1);
}

/****************************************************************************
 * Name: vhost_set_timeout
 ****************************************************************************/

static JSValue vhost_set_timeout(JSContext *ctx, JSValueConst this_val,
                                 int argc, JSValueConst *argv)
{
  return vhost_set_timer(ctx, this_val, argc, argv, false);
}

/****************************************************************************
 * Name: vhost_set_interval
 ****************************************************************************/

static JSValue vhost_set_interval(JSContext *ctx, JSValueConst this_val,
                                  int argc, JSValueConst *argv)
{
  return vhost_set_timer(ctx, this_val, argc, argv, true);
}

/****************************************************************************
 * Name: vhost_clear_timer
 ****************************************************************************/

static JSValue vhost_clear_timer(JSContext *ctx, JSValueConst this_val,
                                 int argc, JSValueConst *argv)
{
  int32_t id = 0;

  if (argc < 1 || JS_ToInt32(ctx, &id, argv[0]))
    {
      return JS_UNDEFINED;
    }

  if (id > 0 && id <= VHOST_MAX_TIMERS && g_timers[id - 1].used)
    {
      struct vhost_timer_s *t = &g_timers[id - 1];
      uv_timer_stop(&t->handle);
      uv_close((uv_handle_t *)&t->handle, NULL);
      JS_FreeValue(t->ctx, t->func);
      t->used = false;
    }

  return JS_UNDEFINED;
}

/****************************************************************************
 * Name: vhost_eval_boot
 ****************************************************************************/

int vhost_eval_boot(JSContext *ctx, const char *app_js, const char *page_js)
{
  char *script;
  size_t cap;
  int len;
  JSValue ret;

  cap = strlen(app_js) + strlen(page_js) + 256;
  script = malloc(cap);
  if (script == NULL)
    {
      fprintf(stderr, "[vapp] OOM for boot script\n");
      return -1;
    }

  len = snprintf(script, cap,
    "import appFactory from '%s';\n"
    "import pageFactory from '%s';\n"
    "globalThis.__app_factory = appFactory;\n"
    "globalThis.__page_factory = pageFactory;\n",
    app_js, page_js);
  if (len < 0 || (size_t)len >= cap)
    {
      free(script);
      return -1;
    }

  ret = JS_Eval(ctx, script, strlen(script), "<boot>",
                JS_EVAL_TYPE_MODULE);
  free(script);

  if (JS_IsException(ret))
    {
      vhost_throw(ctx, "boot");
      return -1;
    }

  JS_FreeValue(ctx, ret);
  return 0;
}

/****************************************************************************
 * Name: vhost_call_factory
 ****************************************************************************/

int vhost_call_factory(JSContext *ctx, const char *gname, JSValue exps)
{
  JSValue g = JS_GetGlobalObject(ctx);
  JSValue factory = JS_GetPropertyStr(ctx, g, gname);
  JSValue global_obj;
  JSValue args[4];
  JSValue ret;
  int status = 0;

  if (JS_IsException(factory) || !JS_IsFunction(ctx, factory))
    {
      fprintf(stderr, "[vapp] %s is not a function\n", gname);
      JS_FreeValue(ctx, factory);
      JS_FreeValue(ctx, g);
      return -1;
    }

  global_obj = JS_GetGlobalObject(ctx);
  args[0] = global_obj;      /* global  */
  args[1] = global_obj;      /* globalThis */
  args[2] = global_obj;      /* window  */
  args[3] = exps;            /* $app_exports$ */

  ret = JS_Call(ctx, factory, JS_UNDEFINED, 4, args);
  if (JS_IsException(ret))
    {
      vhost_throw(ctx, gname);
      status = -1;
    }

  JS_FreeValue(ctx, ret);
  JS_FreeValue(ctx, factory);
  JS_FreeValue(ctx, global_obj);
  JS_FreeValue(ctx, g);
  return status;
}

/****************************************************************************
 * Name: vhost_init
 ****************************************************************************/

void vhost_init(JSContext *ctx, uv_loop_t *loop)
{
  JSValue g = JS_GetGlobalObject(ctx);
  JSValue console = JS_NewObject(ctx);
  JSValue aiot = JS_NewObject(ctx);
  JSValue ce;
  int i;

  g_uvloop = loop;
  for (i = 0; i < VHOST_MAX_TIMERS; i++)
    {
      g_timers[i].used = false;
    }

  /* console.log / info / warn / error */

  ce = JS_NewCFunction(ctx, vhost_console_log, "log", 0);
  JS_SetPropertyStr(ctx, console, "log", JS_DupValue(ctx, ce));
  JS_SetPropertyStr(ctx, console, "info", JS_DupValue(ctx, ce));
  JS_SetPropertyStr(ctx, console, "warn", JS_DupValue(ctx, ce));
  JS_SetPropertyStr(ctx, console, "error", JS_DupValue(ctx, ce));
  JS_FreeValue(ctx, ce);
  JS_SetPropertyStr(ctx, g, "console", console);

  /* Timers driven by the libuv loop */

  JS_SetPropertyStr(ctx, g, "setTimeout",
                    JS_NewCFunction(ctx, vhost_set_timeout,
                                    "setTimeout", 2));
  JS_SetPropertyStr(ctx, g, "setInterval",
                    JS_NewCFunction(ctx, vhost_set_interval,
                                    "setInterval", 2));
  JS_SetPropertyStr(ctx, g, "clearTimeout",
                    JS_NewCFunction(ctx, vhost_clear_timer,
                                    "clearTimeout", 1));
  JS_SetPropertyStr(ctx, g, "clearInterval",
                    JS_NewCFunction(ctx, vhost_clear_timer,
                                    "clearInterval", 1));

  /* aiot.__ce__: the virtual-DOM factory used by compiled rpk templates.
   * Signature: __ce__(tag, props, children) -> plain VDOM object.
   * props carries { __vm__, __opts__ } where __opts__ holds classList,
   * value, src, style etc. Produced by the aiot-toolkit compiler.
   */

  ce = JS_Eval(ctx,
    "(function(tag, props, children) {"
    "  return { __tag: tag, __props: props || {},"
    "           __children: children || [] };"
    "})",
    strlen("(function(tag, props, children) {"
           "  return { __tag: tag, __props: props || {},"
           "           __children: children || [] };"
           "})"),
    "<aiot-ce>", JS_EVAL_TYPE_GLOBAL);
  JS_SetPropertyStr(ctx, aiot, "__ce__", ce);
  JS_SetPropertyStr(ctx, g, "aiot", aiot);

  JS_FreeValue(ctx, g);
}
