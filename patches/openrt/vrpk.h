/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vrpk.h
 *
 * Minimal zip archive reader used to unpack rpk packages. Self-contained
 * on top of zlib inflate (LIB_ZLIB) - no minizip dependency.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.
 ****************************************************************************/

#ifndef __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VRPK_H
#define __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VRPK_H

/****************************************************************************
 * Public Function Prototypes
 ****************************************************************************/

/****************************************************************************
 * Name: vrpk_extract
 *
 * Description:
 *   Extract a zip archive into dest_dir (created as needed), supporting
 *   STORE (0) and DEFLATE (8) entries.
 *
 * Returned Value:
 *   0 on success, -1 on failure.
 ****************************************************************************/

int vrpk_extract(const char *zip_path, const char *dest_dir);

#endif /* __APPS_FRAMEWORKS_RUNTIMES_QUICKAPP_OPENRT_VRPK_H */
