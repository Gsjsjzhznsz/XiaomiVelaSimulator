/****************************************************************************
 * apps/frameworks/runtimes/quickapp/openrt/vstubs.c
 *
 * Compatibility shims for QuickJS on NuttX's libm, which does not
 * implement every C99 math/fenv interface.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.
 ****************************************************************************/

#include <math.h>
#include <fenv.h>

/****************************************************************************
 * Name: lrint
 *
 * Description:
 *   Round-to-nearest with halfway-away-from-zero behaviour. Good enough
 *   for QuickJS' uint8 clamping and float->string formatting.
 ****************************************************************************/

long lrint(double x)
{
  return (long)(x >= 0 ? x + 0.5 : x - 0.5);
}

long lrintf(float x)
{
  return (long)(x >= 0 ? x + 0.5f : x - 0.5f);
}

/****************************************************************************
 * Name: fesetround
 *
 * Description:
 *   NuttX uses the default round-to-nearest mode; other modes are
 *   unsupported, so accept and ignore the request.
 ****************************************************************************/

int fesetround(int rounding_mode)
{
  (void)rounding_mode;
  return 0;
}

/****************************************************************************
 * Additional C99 math helpers used by QuickJS that NuttX libm lacks.
 ****************************************************************************/

double hypot(double x, double y)
{
  double ax = fabs(x);
  double ay = fabs(y);
  double t;

  if (ax == 0.0)
    {
      return ay;
    }

  if (ay == 0.0)
    {
      return ax;
    }

  if (ay > ax)
    {
      t = ax;
      ax = ay;
      ay = t;
    }

  t = ay / ax;
  return ax * sqrt(1.0 + t * t);
}

float hypotf(float x, float y)
{
  return (float)hypot((double)x, (double)y);
}

double log1p(double x)
{
  /* Series/identity: log(1+x) = log(1+x) with cancellation control */

  double u = 1.0 + x;
  if (u == 1.0)
    {
      return x;
    }

  return log(u) * (x / (u - 1.0));
}

float log1pf(float x)
{
  return (float)log1p((double)x);
}

double expm1(double x)
{
  double u = exp(x);
  if (u == 1.0)
    {
      return x;
    }

  if (x <= -1.0)
    {
      return (u - 1.0) * x / (x + 1.0);
    }

  return (u - 1.0) * x / log(u);
}

float expm1f(float x)
{
  return (float)expm1((double)x);
}
