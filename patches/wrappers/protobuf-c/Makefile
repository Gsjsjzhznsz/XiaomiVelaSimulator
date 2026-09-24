############################################################################
# external/protobuf-c/Makefile
############################################################################

include $(APPDIR)/Make.defs

CSRCS += protobuf-c/protobuf-c.c

CFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/external/protobuf-c"
CFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/external/protobuf-c/protobuf-c"
CFLAGS += -Wno-shadow -Wno-undef

include $(APPDIR)/Application.mk
