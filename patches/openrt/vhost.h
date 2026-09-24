/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vhost.h
 *
 * Open-source QuickApp runtime host for the Vela simulator firmware.
 * Provides the JS host environment (console, timers, aiot.__ce__ and the
 * page bootstrap script) that aiot-toolkit compiled rpk bundles expect.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.
 ****************************************************************************/

#ifndef __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VHOST_H
#define __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VHOST_H

#include <uv.h>
#include <quickjs/quickjs.h>

/****************************************************************************
 * Public Function Prototypes
 ****************************************************************************/

/****************************************************************************
 * Name: vhost_init
 *
 * Description:
 *   Install the host environment onto the QuickJS context:
 *   console.log/info/warn/error, setTimeout/setInterval/clearTimeout/
 *   clearInterval (driven by the libuv loop), and the aiot.__ce__
 *   virtual-DOM factory used by compiled rpk templates.
 *
 * Input Parameters:
 *   ctx  - QuickJS context
 *   loop - libuv loop used to drive JS timers
 *
 * Returned Value:
 *   None
 ****************************************************************************/

void vhost_init(JSContext *ctx, uv_loop_t *loop);

/****************************************************************************
 * Name: vhost_eval_boot
 *
 * Description:
 *   Evaluate an ESM script that imports the given rpk modules and stores
 *   their default-export factory functions on globalThis:
 *
 *     globalThis.__app_factory   - app.js bootstrap factory
 *     globalThis.__page_factory  - entry page factory
 *
 * Input Parameters:
 *   ctx       - QuickJS context
 *   app_js    - absolute path to app.js
 *   page_js   - absolute path to the entry page module
 *
 * Returned Value:
 *   0 on success, -1 on failure (exception printed to console)
 ****************************************************************************/

int vhost_eval_boot(JSContext *ctx, const char *app_js, const char *page_js);

/****************************************************************************
 * Name: vhost_call_factory
 *
 * Description:
 *   Invoke globalThis.<gname> (a module factory) with the standard rpk
 *   arguments (global, globalThis, window, exports, evaluate).
 *
 * Input Parameters:
 *   ctx   - QuickJS context
 *   gname - global property holding the factory
 *   exps  - exports object passed as $app_exports$
 *
 * Returned Value:
 *   0 on success, -1 on failure (exception printed)
 ****************************************************************************/

int vhost_call_factory(JSContext *ctx, const char *gname, JSValue exps);

#endif /* __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VHOST_H */
