/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vrender.h
 *
 * Render tree (JSON) -> LVGL widget tree mapping for the open-source
 * QuickApp runtime.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.
 ****************************************************************************/

#ifndef __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VRENDER_H
#define __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VRENDER_H

#include <lvgl/lvgl.h>
#include <netutils/cJSON.h>

/****************************************************************************
 * Name: vrender_tree
 *
 * Description:
 *   Render a JSON render tree (produced by the runtime boot script from
 *   the compiled rpk template + style table) into LVGL widgets.
 *
 *   Tree node format:
 *   {
 *     "tag": "div" | "text" | "image" | ...,
 *     "class": "a b",
 *     "value": "text content (text nodes)",
 *     "src": "image source (image nodes)",
 *     "style": { fontSize: "40px", color: "#ffffff", ... },
 *     "children": [ ... ]
 *   }
 *
 * Input Parameters:
 *   root - LVGL object acting as the page root container
 *   tree - parsed cJSON tree
 *
 * Returned Value:
 *   Number of widgets created, or -1 on error.
 ****************************************************************************/

int vrender_tree(lv_obj_t *root, const cJSON *tree);

#endif /* __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VRENDER_H */
