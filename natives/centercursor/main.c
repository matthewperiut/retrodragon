#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <wayland-client.h>
#include "pointer-constraints-client-protocol.h"
#include "pointer-warp-client-protocol.h"

static struct wl_pointer *lib_ptr;
static struct zwp_pointer_constraints_v1 *lib_pcon;
static struct wp_pointer_warp_v1 *lib_warp;
static struct wl_seat *lib_seat;
static struct wl_event_queue *lib_queue;
static struct wl_registry *lib_registry;
static int lib_inited;
static int lib_pointer_has_focus;
static uint32_t lib_enter_serial;
static int lib_is_gnome;

struct warp_data {
    int x, y;
    struct wl_surface *surf;
    int done;
};

static struct warp_data wd;

/* ── Locked pointer listener (KDE mode) ── */
static int lib_locked;
static void lib_lock_locked_kde(void *d, struct zwp_locked_pointer_v1 *lk) {
    zwp_locked_pointer_v1_set_cursor_position_hint(lk,
        wl_fixed_from_int(wd.x), wl_fixed_from_int(wd.y));
    wl_surface_commit(wd.surf);
    lib_locked = 1;
}
static void lib_lock_unlocked_kde(void *d, struct zwp_locked_pointer_v1 *lk) {
    (void)d; (void)lk;
}
static const struct zwp_locked_pointer_v1_listener lib_lock_ls_kde = {
    lib_lock_locked_kde, lib_lock_unlocked_kde
};

/* ── Locked pointer listener (GNOME mode) ── */
static void lib_lock_locked_gnome(void *d, struct zwp_locked_pointer_v1 *lk) {
    zwp_locked_pointer_v1_set_cursor_position_hint(lk,
        wl_fixed_from_int(wd.x), wl_fixed_from_int(wd.y));
    wl_surface_commit(wd.surf);
    zwp_locked_pointer_v1_destroy(lk);
    wl_surface_commit(wd.surf);
    wd.done = 1;
}
static void lib_lock_unlocked_gnome(void *d, struct zwp_locked_pointer_v1 *lk) {
    (void)d; (void)lk;
}
static const struct zwp_locked_pointer_v1_listener lib_lock_ls_gnome = {
    lib_lock_locked_gnome, lib_lock_unlocked_gnome
};

/* ── Pointer listener (focus + enter serial tracking) ── */
static void lp_enter(void *d, struct wl_pointer *p, uint32_t serial, struct wl_surface *sf, wl_fixed_t x, wl_fixed_t y) {
    lib_pointer_has_focus = 1;
    lib_enter_serial = serial;
}
static void lp_leave(void *d, struct wl_pointer *p, uint32_t serial, struct wl_surface *sf) {
    lib_pointer_has_focus = 0;
}
static void lp_motion(void *d, struct wl_pointer *p, uint32_t t, wl_fixed_t x, wl_fixed_t y) {}
static void lp_button(void *d, struct wl_pointer *p, uint32_t s, uint32_t t, uint32_t b, uint32_t st) {}
static void lp_axis(void *d, struct wl_pointer *p, uint32_t t, uint32_t a, wl_fixed_t v) {}
static const struct wl_pointer_listener lp_ls = { lp_enter, lp_leave, lp_motion, lp_button, lp_axis };

/* ── Seat listener ── */
static void lib_seat_caps(void *d, struct wl_seat *s, uint32_t c) {
    if (c & WL_SEAT_CAPABILITY_POINTER) {
        lib_ptr = wl_seat_get_pointer(s);
        wl_proxy_set_queue((struct wl_proxy *)lib_ptr, lib_queue);
        wl_pointer_add_listener(lib_ptr, &lp_ls, NULL);
    }
}
static void lib_seat_name(void *d, struct wl_seat *s, const char *n) {}
static const struct wl_seat_listener lib_seat_ls = { lib_seat_caps, lib_seat_name };

/* ── Registry listener ── */
static void lib_reg_global(void *d, struct wl_registry *r, uint32_t n, const char *i, uint32_t v) {
    if (!strcmp(i, "wl_seat")) {
        lib_seat = wl_registry_bind(r, n, &wl_seat_interface, 1);
        wl_proxy_set_queue((struct wl_proxy *)lib_seat, lib_queue);
        wl_seat_add_listener(lib_seat, &lib_seat_ls, NULL);
    } else if (!strcmp(i, "zwp_pointer_constraints_v1")) {
        lib_pcon = wl_registry_bind(r, n, &zwp_pointer_constraints_v1_interface, 1);
        wl_proxy_set_queue((struct wl_proxy *)lib_pcon, lib_queue);
    } else if (!strcmp(i, "wp_pointer_warp_v1")) {
        lib_warp = wl_registry_bind(r, n, &wp_pointer_warp_v1_interface, 1);
        wl_proxy_set_queue((struct wl_proxy *)lib_warp, lib_queue);
    }
}
static void lib_reg_remove(void *d, struct wl_registry *r, uint32_t n) {}
static const struct wl_registry_listener lib_reg_ls = { lib_reg_global, lib_reg_remove };

static void lib_init(struct wl_display *dpy) {
    if (lib_inited) return;
    lib_queue = wl_display_create_queue(dpy);
    lib_registry = wl_display_get_registry(dpy);
    wl_proxy_set_queue((struct wl_proxy *)lib_registry, lib_queue);
    wl_registry_add_listener(lib_registry, &lib_reg_ls, NULL);
    wl_display_roundtrip_queue(dpy, lib_queue);
    wl_display_roundtrip_queue(dpy, lib_queue);
    lib_inited = 1;
}

/* ── Warp approaches ── */

static int do_pointer_warp(struct wl_display *dpy) {
    if (!lib_warp) return 0;
    wp_pointer_warp_v1_warp_pointer(lib_warp, wd.surf, lib_ptr,
        wl_fixed_from_int(wd.x), wl_fixed_from_int(wd.y),
        lib_enter_serial);
    wl_display_roundtrip_queue(dpy, lib_queue);
    return 1;
}

static int do_lock_warp_kde(struct wl_display *dpy) {
    if (!lib_pcon) return 0;
    lib_locked = 0;

    struct zwp_locked_pointer_v1 *lk = zwp_pointer_constraints_v1_lock_pointer(
        lib_pcon, wd.surf, lib_ptr, NULL,
        ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT);
    if (!lk) return 0;

    zwp_locked_pointer_v1_set_cursor_position_hint(lk,
        wl_fixed_from_int(wd.x), wl_fixed_from_int(wd.y));
    wl_surface_commit(wd.surf);

    wl_proxy_set_queue((struct wl_proxy *)lk, lib_queue);
    zwp_locked_pointer_v1_add_listener(lk, &lib_lock_ls_kde, NULL);

    int tries = 0;
    while (!lib_locked && tries < 5) {
        wl_display_roundtrip_queue(dpy, lib_queue);
        tries++;
    }

    if (!lib_locked) {
        zwp_locked_pointer_v1_destroy(lk);
        wl_display_roundtrip_queue(dpy, lib_queue);
        return 0;
    }

    wl_display_roundtrip_queue(dpy, lib_queue);
    zwp_locked_pointer_v1_destroy(lk);
    wl_surface_commit(wd.surf);
    wl_display_roundtrip_queue(dpy, lib_queue);
    return 1;
}

static int do_lock_warp_gnome(struct wl_display *dpy) {
    if (!lib_pcon) return 0;
    wd.done = 0;

    struct zwp_locked_pointer_v1 *lk = zwp_pointer_constraints_v1_lock_pointer(
        lib_pcon, wd.surf, lib_ptr, NULL,
        ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT);
    if (!lk) return 0;

    wl_proxy_set_queue((struct wl_proxy *)lk, lib_queue);
    zwp_locked_pointer_v1_add_listener(lk, &lib_lock_ls_gnome, &wd);

    int tries = 0;
    while (!wd.done && tries < 5) {
        wl_display_roundtrip_queue(dpy, lib_queue);
        tries++;
    }
    if (!wd.done) {
        zwp_locked_pointer_v1_destroy(lk);
    }

    wl_display_roundtrip_queue(dpy, lib_queue);
    return 1;
}

/* ── Library entry points ── */

__attribute__((visibility("default")))
void wl_set_gnome(int is_gnome) {
    lib_is_gnome = is_gnome;
}

__attribute__((visibility("default")))
void wl_setup_warp(void *wl_display, void *wl_surface, int x, int y) {
    struct wl_display *dpy = wl_display;
    lib_init(dpy);
    wl_display_roundtrip_queue(dpy, lib_queue);

    if (!lib_pointer_has_focus) return;

    wd.x = x;
    wd.y = y;
    wd.surf = wl_surface;
    wd.done = 0;
}

__attribute__((visibility("default")))
void wl_finish_warp(void *wl_display) {
    struct wl_display *dpy = wl_display;
    if (!lib_queue || !lib_ptr || !wd.surf) return;

    wl_display_roundtrip_queue(dpy, lib_queue);
    if (!lib_pointer_has_focus) {
        wd.surf = NULL;
        return;
    }

    if (lib_is_gnome) {
        do_lock_warp_gnome(dpy);
    } else {
        if (!do_pointer_warp(dpy)) {
            do_lock_warp_kde(dpy);
        }
    }

    wd.surf = NULL;
}

__attribute__((visibility("default")))
void wl_reset(void) {
    lib_pointer_has_focus = 0;
    wd.surf = NULL;
    wd.done = 0;
}
