############################################################################
# external/libpng/Makefile
############################################################################

include $(APPDIR)/Make.defs

CSRCS += $(wildcard *.c)

# zlib headers for pngstruct consumers; keep NEON asm paths off
CFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/system/zlib/zlib"
CFLAGS += -Wno-shadow -Wno-undef

include $(APPDIR)/Application.mk
