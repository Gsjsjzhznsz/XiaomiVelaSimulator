/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vrpk.c
 *
 * Minimal zip archive reader used to unpack rpk packages. Self-contained
 * on top of zlib inflate (LIB_ZLIB) - no minizip dependency.
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
#include <sys/stat.h>
#include <sys/types.h>

#include <zlib.h>

#include "vrpk.h"

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

#define EOCD_SIG   0x06054b50UL
#define CEN_SIG    0x02014b50UL
#define LOC_SIG    0x04034b50UL

#define EOCD_MIN   22
#define CEN_HDR    46
#define LOC_HDR    30

/****************************************************************************
 * Private Functions
 ****************************************************************************/

static uint16_t rd16(const uint8_t *p)
{
  return (uint16_t)(p[0] | (p[1] << 8));
}

static uint32_t rd32(const uint8_t *p)
{
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
         ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* Read size bytes at offset into a malloc buffer; NULL on failure.
 * NOTE: reads in 512-byte chunks - the NuttX FAT driver can fail
 * (EIO) on large single reads spanning FAT cluster chains.
 */

#define VRPK_CHUNK 512

static uint8_t *slurp(FILE *fp, long offset, size_t size)
{
  uint8_t *buf;
  size_t done = 0;

  buf = (uint8_t *)malloc(size ? size : 1);
  if (buf == NULL)
    {
      return NULL;
    }

  if (fseek(fp, offset, SEEK_SET) != 0)
    {
      free(buf);
      return NULL;
    }

  while (done < size)
    {
      size_t chunk = size - done;
      size_t got;

      if (chunk > VRPK_CHUNK)
        {
          chunk = VRPK_CHUNK;
        }

      got = fread(buf + done, 1, chunk, fp);
      if (got == 0)
        {
          printf("[vrpk] slurp failed at %ld+%zu/%zu: %s\n",
                 offset, done, size, strerror(errno));
          free(buf);
          return NULL;
        }

      done += got;
    }

  return buf;
}

/* Join dest_dir and name without producing duplicated slashes.
 * FAT rejects paths containing '//'. */

static void vrpk_join(const char *dest, const char *name, char *out,
                      size_t outlen)
{
  size_t dl = strlen(dest);

  while (dl > 1 && dest[dl - 1] == '/')
    {
      dl--;
    }

  snprintf(out, outlen, "%.*s/%s", (int)dl, dest, name);
}

/* Find the End Of Central Directory record within the tail buffer. */

static const uint8_t *find_eocd(const uint8_t *tail, size_t tail_size)
{
  ssize_t i;

  if (tail_size < EOCD_MIN)
    {
      return NULL;
    }

  for (i = (ssize_t)tail_size - EOCD_MIN; i >= 0; i--)
    {
      if (rd32(tail + i) == EOCD_SIG)
        {
          /* Validate that the comment length fits the buffer. */

          size_t clen = rd16(tail + i + 20);
          if ((size_t)i + EOCD_MIN + clen <= tail_size)
            {
              return tail + i;
            }
        }
    }

  return NULL;
}

/* Inflate a raw deflate stream (no zlib header) into out (usize bytes). */

static int inflate_raw(const uint8_t *src, size_t csize,
                       uint8_t *dst, size_t usize)
{
  z_stream zs;
  int err;

  memset(&zs, 0, sizeof(zs));
  zs.next_in = (Bytef *)src;
  zs.avail_in = (uInt)csize;
  zs.next_out = dst;
  zs.avail_out = (uInt)usize;

  /* -MAX_WBITS: raw deflate */

  err = inflateInit2(&zs, -MAX_WBITS);
  if (err != Z_OK)
    {
      return -1;
    }

  err = inflate(&zs, Z_FINISH);
  inflateEnd(&zs);

  if (err != Z_STREAM_END || zs.total_out != usize)
    {
      return -1;
    }

  return 0;
}

/* mkdir -p equivalent for a path under dest_dir. */

static void vrpk_mkdirs(const char *path)
{
  char tmp[512];
  char *p;

  snprintf(tmp, sizeof(tmp), "%s", path);
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
}

/* mkdir -p for the PARENT directory of a file path (the file itself
 * must not be created as a directory). */

static void vrpk_mkdirs_parent(const char *filepath)
{
  char tmp[512];
  char *slash;

  snprintf(tmp, sizeof(tmp), "%s", filepath);
  slash = strrchr(tmp, '/');
  if (slash == NULL)
    {
      return;
    }

  if (slash == tmp)
    {
      return;              /* parent is "/" */
    }

  *slash = '\0';
  vrpk_mkdirs(tmp);
}

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int vrpk_extract(const char *zip_path, const char *dest_dir)
{
  FILE *fp;
  long fsize;
  size_t tail_size;
  uint8_t *tail;
  const uint8_t *eocd;
  uint16_t entries;
  uint32_t cd_ofs;
  uint8_t *cd;
  size_t cd_size;
  size_t pos;
  uint16_t i;
  int ret = 0;

  fp = fopen(zip_path, "r");
  if (fp == NULL)
    {
      printf("[vrpk] cannot open %s: %s\n", zip_path, strerror(errno));
      return -1;
    }

  /* Determine file size and slurp the tail (EOCD lives in the last 64K). */

  if (fseek(fp, 0, SEEK_END) != 0)
    {
      fclose(fp);
      return -1;
    }

  fsize = ftell(fp);
  if (fsize < EOCD_MIN)
    {
      fclose(fp);
      return -1;
    }

  tail_size = (fsize > 65536) ? 65536 : (size_t)fsize;
  tail = slurp(fp, fsize - (long)tail_size, tail_size);
  if (tail == NULL)
    {
      fclose(fp);
      return -1;
    }

  eocd = find_eocd(tail, tail_size);
  if (eocd == NULL)
    {
      printf("[vrpk] EOCD not found in %s\n", zip_path);
      free(tail);
      fclose(fp);
      return -1;
    }

  entries = rd16(eocd + 10);
  cd_size = rd32(eocd + 12);
  cd_ofs  = rd32(eocd + 16);

  cd = slurp(fp, (long)cd_ofs, cd_size);
  free(tail);
  if (cd == NULL)
    {
      fclose(fp);
      return -1;
    }

  /* Walk central directory entries. */

  pos = 0;
  for (i = 0; i < entries && pos + CEN_HDR <= cd_size; i++)
    {
      const uint8_t *cen = cd + pos;
      uint32_t sig = rd32(cen);

      if (sig != CEN_SIG)
        {
          break;
        }

      uint16_t method    = rd16(cen + 10);
      uint32_t csize     = rd32(cen + 20);
      uint32_t usize     = rd32(cen + 24);
      uint16_t nlen      = rd16(cen + 28);
      uint16_t elen      = rd16(cen + 30);
      uint16_t clen      = rd16(cen + 32);
      uint32_t loc_ofs   = rd32(cen + 42);
      uint16_t flags     = rd16(cen + 8);

      size_t ent_total = (size_t)CEN_HDR + nlen + elen + clen;
      if (pos + ent_total > cd_size)
        {
          break;
        }

      pos += ent_total;

      /* Name (not NUL terminated in the archive). */

      char name[512];
      if (nlen == 0 || nlen >= sizeof(name))
        {
          continue;
        }

      memcpy(name, cen + CEN_HDR, nlen);
      name[nlen] = '\0';

      if (name[nlen - 1] == '/')
        {
          /* Directory entry. */

          char dpath[512];
          vrpk_join(dest_dir, name, dpath, sizeof(dpath));
          printf("[vrpk] dir  %s\n", dpath);
          vrpk_mkdirs(dpath);
          continue;
        }

      /* Skip zip64 placeholders and encrypted entries. */

      if (csize == 0xffffffffUL || usize == 0xffffffffUL ||
          (flags & 0x1) != 0)
        {
          printf("[vrpk] unsupported entry: %s\n", name);
          ret = -1;
          break;
        }

      /* Read local header to locate the data. */

      {
        uint8_t *lh = slurp(fp, (long)loc_ofs, LOC_HDR);
        if (lh == NULL)
          {
            ret = -1;
            break;
          }

        if (rd32(lh) != LOC_SIG)
          {
            free(lh);
            ret = -1;
            break;
          }

        uint16_t lnlen = rd16(lh + 26);
        uint16_t lelen = rd16(lh + 28);
        long data_ofs = (long)loc_ofs + LOC_HDR + lnlen + lelen;
        free(lh);

        uint8_t *data = slurp(fp, data_ofs, csize);
        if (data == NULL)
          {
            ret = -1;
            break;
          }

        char fpath[512];
        vrpk_join(dest_dir, name, fpath, sizeof(fpath));
        vrpk_mkdirs_parent(fpath); /* create parents only, not the file */

        printf("[vrpk] file %s (%lu -> %lu bytes, method %u)\n",
               fpath, (unsigned long)csize, (unsigned long)usize, method);

        FILE *out = fopen(fpath, "w");
        if (out == NULL)
          {
            printf("[vrpk] cannot write %s: %s\n", fpath,
                   strerror(errno));
            free(data);
            ret = -1;
            break;
          }

        int ok = 0;
        if (method == 0)
          {
            ok = (fwrite(data, 1, csize, out) == csize);
          }
        else if (method == 8)
          {
            uint8_t *ubuf = (uint8_t *)malloc(usize ? usize : 1);
            if (ubuf == NULL)
              {
                ok = 0;
              }
            else if (inflate_raw(data, csize, ubuf, usize) == 0)
              {
                ok = (fwrite(ubuf, 1, usize, out) == usize);
              }
            else
              {
                printf("[vrpk] inflate failed: %s\n", name);
                ok = 0;
              }
            free(ubuf);
          }
        else
          {
            printf("[vrpk] unsupported method %d: %s\n",
                    method, name);
            ok = 0;
          }

        fclose(out);
        free(data);

        if (!ok)
          {
            ret = -1;
            break;
          }
      }
    }

  free(cd);
  fclose(fp);
  return ret;
}
