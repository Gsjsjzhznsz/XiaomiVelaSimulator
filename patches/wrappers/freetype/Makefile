############################################################################
# external/freetype/freetype/Makefile
############################################################################

include $(APPDIR)/Make.defs

FTROOT = src

CSRCS += $(FTROOT)/base/ftsystem.c
CSRCS += $(FTROOT)/base/ftinit.c
CSRCS += $(FTROOT)/base/ftdebug.c
CSRCS += $(FTROOT)/base/ftbase.c
CSRCS += $(FTROOT)/base/ftbbox.c
CSRCS += $(FTROOT)/base/ftbitmap.c
CSRCS += $(FTROOT)/base/ftglyph.c
CSRCS += $(FTROOT)/sfnt/sfnt.c
CSRCS += $(FTROOT)/truetype/truetype.c
CSRCS += $(FTROOT)/cff/cff.c
CSRCS += $(FTROOT)/psnames/psnames.c
CSRCS += $(FTROOT)/psaux/psaux.c
CSRCS += $(FTROOT)/pshinter/pshinter.c
CSRCS += $(FTROOT)/autofit/autofit.c
CSRCS += $(FTROOT)/gzip/ftgzip.c
CSRCS += $(FTROOT)/smooth/smooth.c

CFLAGS += -DFT2_BUILD_LIBRARY
CFLAGS += -Wno-shadow -Wno-undef -Wno-unused-but-set-variable

include $(APPDIR)/Application.mk
