############################################################################
# external/curl/Makefile
############################################################################

include $(APPDIR)/Make.defs

CSRCS += $(filter-out lib/formdata.c, $(wildcard lib/*.c))

CFLAGS += -DHAVE_CONFIG_H -DBUILDING_LIBCURL
CFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/external/curl/lib"
CFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/external/curl/include"
CFLAGS += -Wno-shadow -Wno-undef -Wno-unused-variable

include $(APPDIR)/Application.mk
