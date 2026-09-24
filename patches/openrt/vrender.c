/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vrender.c
 *
 * Open-source QuickApp runtime renderer for the Vela simulator firmware.
 * Maps the JSON render tree produced from compiled rpk templates onto
 * LVGL (v9) widgets.
 *
 * Supported tags: div (view container), text, image, input (read-only
 * label fallback), span (as text).
 *
 * Supported styles (quickapp camelCase):
 *   width, height                ("100px", "50%", number)
 *   backgroundColor, background  ("#rrggbb")
 *   color                        (text color)
 *   fontSize                     ("40px" -> nearest Montserrat font)
 *   flexDirection                (column/row)
 *   justifyContent               (flex-start/center/flex-end/
 *                                 space-between/space-around)
 *   alignItems                   (flex-start/center/flex-end/stretch)
 *   marginTop/Left/Right/Bottom, padding*  (spacing)
 *   borderRadius                 (corner radius)
 *   borderWidth, borderColor
 *   textAlign                    (center/left/right)
 *   opacity                      (0..1)
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

#include <lvgl/lvgl.h>
#include "vrender.h"

/****************************************************************************
 * Private Types
 ****************************************************************************/

struct vstyle_s
{
  const char *width;
  const char *height;
  const char *bg;
  const char *color;
  const char *font_size;
  const char *flex_dir;
  const char *justify;
  const char *align;
  const char *margin_top;
  const char *margin_left;
  const char *margin_right;
  const char *margin_bottom;
  const char *radius;
  const char *border_width;
  const char *border_color;
  const char *text_align;
  const char *opacity;
};

/****************************************************************************
 * Private Functions
 ****************************************************************************/

/****************************************************************************
 * Name: parse_px
 *
 * Description:
 *   Parse "12px", "12", 12 -> 12. Returns def on parse failure.
 ****************************************************************************/

static int32_t parse_px(const char *s, int32_t def)
{
  char *end;
  long v;

  if (s == NULL)
    {
      return def;
    }

  v = strtol(s, &end, 10);
  if (end == s)
    {
      return def;
    }

  return (int32_t)v;
}

/****************************************************************************
 * Name: parse_color
 *
 * Description:
 *   Parse "#rrggbb" / "#rgb" / "#aarrggbb" colors. Returns false if the
 *   string is not a literal color (leave the style untouched then).
 ****************************************************************************/

static bool parse_color(const char *s, lv_color_t *out)
{
  unsigned long v;
  char *end;

  if (s == NULL || s[0] != '#')
    {
      return false;
    }

  v = strtoul(s + 1, &end, 16);
  if (strlen(s + 1) == 3)
    {
      /* #rgb -> expand */

      unsigned long r = (v >> 8) & 0xf;
      unsigned long g = (v >> 4) & 0xf;
      unsigned long b = v & 0xf;
      v = (r << 20) | (r << 16) | (g << 12) | (g << 8) | (b << 4) | b;
    }

  /* Treat "#rrggbb" as opaque (add full alpha for the 8-digit case) */

  if (end == s + 1)
    {
      return false;
    }

  *out = lv_color_hex(v & 0xffffffu);
  return true;
}

/****************************************************************************
 * Name: parse_pct
 *
 * Description:
 *   Detect percentage sizes: "50%" -> 50, false if not a percentage.
 ****************************************************************************/

static bool parse_pct(const char *s, int32_t *out)
{
  const char *pct;
  char *end;
  long v;

  if (s == NULL)
    {
      return false;
    }

  pct = strchr(s, '%');
  if (pct == NULL)
    {
      return false;
    }

  v = strtol(s, &end, 10);
  if (end == s)
    {
      return false;
    }

  *out = (int32_t)v;
  return true;
}

/****************************************************************************
 * Name: pick_font
 *
 * Description:
 *   Pick the closest enabled Montserrat font for a pixel size.
 ****************************************************************************/

static const lv_font_t *pick_font(int32_t px)
{
  static const struct
  {
    int32_t px;
    const lv_font_t *font;
  } table[] =
  {
    { 14, &lv_font_montserrat_14 },
    { 16, &lv_font_montserrat_16 },
    { 24, &lv_font_montserrat_24 },
    { 32, &lv_font_montserrat_32 },
    { 40, &lv_font_montserrat_40 },
    { 48, &lv_font_montserrat_48 },
  };

  const lv_font_t *best = table[0].font;
  int bestdiff = 0x7fffffff;
  int i;

  for (i = 0; i < 6; i++)
    {
      int diff = table[i].px - px;
      if (diff < 0)
        {
          diff = -diff;
        }

      if (diff < bestdiff)
        {
          bestdiff = diff;
          best = table[i].font;
        }
    }

  return best;
}

/****************************************************************************
 * Name: apply_style
 ****************************************************************************/

static void apply_style(lv_obj_t *obj, const struct vstyle_s *st)
{
  lv_color_t col;
  int32_t px;

  /* NOTE: use object-local styles (lv_obj_set_style_*) - they are stored
   * inside the object itself. The previous implementation registered a
   * stack-allocated lv_style_t, leaving the object with a dangling style
   * pointer (undefined rendering). */

  if (parse_color(st->bg, &col))
    {
      lv_obj_set_style_bg_color(obj, col, 0);
      lv_obj_set_style_bg_opa(obj, LV_OPA_COVER, 0);
    }

  if (parse_color(st->color, &col))
    {
      lv_obj_set_style_text_color(obj, col, 0);
    }

  if (st->font_size != NULL)
    {
      px = parse_px(st->font_size, -1);
      if (px > 0)
        {
          lv_obj_set_style_text_font(obj, pick_font(px), 0);
        }
    }

  if (st->width != NULL)
    {
      if (parse_pct(st->width, &px))
        {
          lv_obj_set_style_width(obj, lv_pct(px), 0);
        }
      else
        {
          lv_obj_set_style_width(obj, parse_px(st->width, -1), 0);
        }
    }

  if (st->height != NULL)
    {
      if (parse_pct(st->height, &px))
        {
          lv_obj_set_style_height(obj, lv_pct(px), 0);
        }
      else
        {
          lv_obj_set_style_height(obj, parse_px(st->height, -1), 0);
        }
    }

  if (st->radius != NULL)
    {
      lv_obj_set_style_radius(obj, parse_px(st->radius, 0), 0);
    }

  if (st->border_width != NULL)
    {
      lv_obj_set_style_border_width(obj, parse_px(st->border_width, 0), 0);
      if (parse_color(st->border_color, &col))
        {
          lv_obj_set_style_border_color(obj, col, 0);
        }
    }

  if (st->opacity != NULL)
    {
      double op = strtod(st->opacity, NULL);
      if (op < 0)
        {
          op = 0;
        }

      if (op > 1)
        {
          op = 1;
        }

      lv_obj_set_style_opa(obj, (lv_opa_t)(op * LV_OPA_COVER), 0);
    }

  if (st->margin_top != NULL)
    {
      lv_obj_set_style_pad_top(obj, parse_px(st->margin_top, 0), 0);
    }

  if (st->margin_bottom != NULL)
    {
      lv_obj_set_style_pad_bottom(obj, parse_px(st->margin_bottom, 0), 0);
    }

  if (st->margin_left != NULL)
    {
      lv_obj_set_style_pad_left(obj, parse_px(st->margin_left, 0), 0);
    }

  if (st->margin_right != NULL)
    {
      lv_obj_set_style_pad_right(obj, parse_px(st->margin_right, 0), 0);
    }
}

/****************************************************************************
 * Name: read_styles
 ****************************************************************************/

static void read_styles(const cJSON *style, struct vstyle_s *st)
{
  const cJSON *v;

  if (style == NULL || !cJSON_IsObject(style))
    {
      return;
    }

#define GETS(field, name) \
  do                                                                    \
    {                                                                   \
      v = cJSON_GetObjectItemCaseSensitive(style, name);                \
      if (cJSON_IsString(v) && v->valuestring != NULL)                  \
        {                                                               \
          st->field = v->valuestring;                                   \
        }                                                               \
      else if (cJSON_IsNumber(v))                                       \
        {                                                               \
          /* Number values: format into a small rotating buffer so */   \
          /* the style string stays valid until apply_style().    */   \
          static char numbuf[32][12];                                   \
          static int slot = 0;                                          \
          slot = (slot + 1) & 31;                                       \
          snprintf(numbuf[slot], sizeof(numbuf[slot]),                  \
                   "%dpx", (int)v->valuedouble);                        \
          st->field = numbuf[slot];                                     \
        }                                                               \
    }                                                                   \
  while (0)

  GETS(width, "width");
  GETS(height, "height");
  GETS(bg, "backgroundColor");
  GETS(bg, "background");
  GETS(color, "color");
  GETS(font_size, "fontSize");
  GETS(flex_dir, "flexDirection");
  GETS(justify, "justifyContent");
  GETS(align, "alignItems");
  GETS(margin_top, "marginTop");
  GETS(margin_left, "marginLeft");
  GETS(margin_right, "marginRight");
  GETS(margin_bottom, "marginBottom");
  GETS(radius, "borderRadius");
  GETS(border_width, "borderWidth");
  GETS(border_color, "borderColor");
  GETS(text_align, "textAlign");
  GETS(opacity, "opacity");

#undef GETS
}

/****************************************************************************
 * Name: vrender_node
 ****************************************************************************/

static int vrender_node(lv_obj_t *parent, const cJSON *node)
{
  const cJSON *tag = cJSON_GetObjectItemCaseSensitive(node, "tag");
  const cJSON *value = cJSON_GetObjectItemCaseSensitive(node, "value");
  const cJSON *src = cJSON_GetObjectItemCaseSensitive(node, "src");
  const cJSON *style = cJSON_GetObjectItemCaseSensitive(node, "style");
  const cJSON *children = cJSON_GetObjectItemCaseSensitive(node,
                                                           "children");
  const cJSON *child;
  struct vstyle_s st;
  lv_obj_t *obj = NULL;
  int count = 1;

  if (!cJSON_IsString(tag) || tag->valuestring == NULL)
    {
      return 0;
    }

  memset(&st, 0, sizeof(st));
  read_styles(style, &st);

  if (strcmp(tag->valuestring, "text") == 0 ||
      strcmp(tag->valuestring, "span") == 0 ||
      strcmp(tag->valuestring, "label") == 0)
    {
      obj = lv_label_create(parent);
      if (cJSON_IsString(value) && value->valuestring != NULL)
        {
          lv_label_set_text(obj, value->valuestring);
        }
    }
  else if (strcmp(tag->valuestring, "image") == 0 ||
           strcmp(tag->valuestring, "img") == 0)
    {
      obj = lv_image_create(parent);
      if (cJSON_IsString(src) && src->valuestring != NULL)
        {
          lv_image_set_src(obj, src->valuestring);
        }
    }
  else
    {
      /* div / view / stack / anything else: plain container */

      obj = lv_obj_create(parent);
      lv_obj_remove_style_all(obj);
    }

  apply_style(obj, &st);

  /* Children */

  if (cJSON_IsArray(children))
    {
      cJSON_ArrayForEach(child, children)
      {
        count += vrender_node(obj, child);
      }
    }

  return count;
}

/****************************************************************************
 * Name: vrender_tree
 ****************************************************************************/

int vrender_tree(lv_obj_t *root, const cJSON *tree)
{
  if (root == NULL || tree == NULL || !cJSON_IsObject(tree))
    {
      return -1;
    }

  return vrender_node(root, tree);
}
