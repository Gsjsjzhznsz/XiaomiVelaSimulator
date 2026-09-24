############################################################################
# external/yoga/Makefile
#
# NuttX apps build has no .cpp suffix rule, so the apply script pre-copies
# yoga/yoga/**/*.cpp into yoga/yoga_cxx/.../*.cxx (same content).
############################################################################

include $(APPDIR)/Make.defs

CXXSRCS += $(wildcard yoga_cxx/*.cxx)
CXXSRCS += $(wildcard yoga_cxx/*/*.cxx)

CFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/external/yoga"
CXXFLAGS += ${INCDIR_PREFIX}"$(APPDIR)/external/yoga"
CXXFLAGS += -Wno-shadow -Wno-undef

include $(APPDIR)/Application.mk
