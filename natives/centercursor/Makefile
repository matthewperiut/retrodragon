WAYLAND_PROTOS = /usr/share/wayland-protocols
SCANNER = wayland-scanner
WL_CFLAGS = $(shell pkg-config --cflags wayland-client)
WL_LIBS   = $(shell pkg-config --libs wayland-client) -lrt

PCON_XML = $(WAYLAND_PROTOS)/unstable/pointer-constraints/pointer-constraints-unstable-v1.xml
PCON_HDR = pointer-constraints-client-protocol.h
PCON_SRC = pointer-constraints-protocol.c

WARP_XML = $(wildcard $(WAYLAND_PROTOS)/staging/pointer-warp/pointer-warp-v1.xml)
ifeq ($(WARP_XML),)
    WARP_XML = pointer-warp-v1.xml
endif
WARP_HDR = pointer-warp-client-protocol.h
WARP_SRC = pointer-warp-protocol.c

GEN_FILES = $(PCON_HDR) $(PCON_SRC) $(WARP_HDR) $(WARP_SRC)

ARCH := $(shell uname -m)
ifeq ($(ARCH),aarch64)
    OUTPUT_DIR = ../../src/main/resources/natives/linux-aarch64
else
    OUTPUT_DIR = ../../src/main/resources/natives/linux-x86_64
endif

all: $(OUTPUT_DIR)/libcenter.so
	@rm -f $(GEN_FILES)

$(OUTPUT_DIR)/libcenter.so: main.c
	@mkdir -p $(OUTPUT_DIR)
	$(SCANNER) client-header $(PCON_XML) $(PCON_HDR)
	$(SCANNER) private-code $(PCON_XML) $(PCON_SRC)
	$(SCANNER) client-header $(WARP_XML) $(WARP_HDR)
	$(SCANNER) private-code $(WARP_XML) $(WARP_SRC)
	$(CC) -shared -fPIC -fvisibility=hidden -o $@ main.c $(PCON_SRC) $(WARP_SRC) $(WL_CFLAGS) $(WL_LIBS)

clean:
	rm -f $(OUTPUT_DIR)/libcenter.so $(GEN_FILES)

.PHONY: all clean
