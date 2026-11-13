/*
 * Mountain Watch 1.1 — native Tizen 4.0 watch application
 * Target: Samsung Galaxy Watch SM-R800.
 *
 * Native rendering foundation: three analog hands only (hour, minute,
 * seconds). Approved photographic artwork and movable widget layers are
 * separate assets and are not yet integrated in this source.
 */
#include <watch_app.h>
#include <app.h>
#include <Elementary.h>
#include <dlog.h>
#include <math.h>
#include <stdbool.h>
#include <stdio.h>

#define LOG_TAG "MOUNTAIN_WATCH"
#define PI 3.14159265358979323846

typedef struct {
    Evas_Object *win;
    Evas_Object *background;
    Evas_Object *hour_hand;
    Evas_Object *minute_hand;
    Evas_Object *second_hand;
    Evas_Object *hub_outer;
    Evas_Object *hub_inner;
    int width;
    int height;
    int cx;
    int cy;
    int radius;
    bool ambient;
} appdata_s;

static void set_hand(Evas_Object *line, int cx, int cy, double angle,
                     double length, int width, int r, int g, int b)
{
    double radians = (angle - 90.0) * PI / 180.0;
    int x2 = cx + (int)(cos(radians) * length);
    int y2 = cy + (int)(sin(radians) * length);
    evas_object_line_xy_set(line, cx, cy, x2, y2);
    evas_object_line_width_set(line, width);
    evas_object_color_set(line, r, g, b, 255);
}

static void update_watch(appdata_s *ad, watch_time_h wt, bool ambient)
{
    int hour = 0, minute = 0, second = 0;
    double second_angle, minute_angle, hour_angle;

    if (!ad || !wt) return;
    watch_time_get_hour24(wt, &hour);
    watch_time_get_minute(wt, &minute);
    watch_time_get_second(wt, &second);

    second_angle = second * 6.0;
    minute_angle = minute * 6.0 + second * 0.1;
    hour_angle = (hour % 12) * 30.0 + minute * 0.5;

    /* The two broad metallic hands and one thin blue seconds hand. */
    set_hand(ad->hour_hand, ad->cx, ad->cy, hour_angle,
             ad->radius * 0.43, ambient ? 5 : 8, 224, 235, 247);
    set_hand(ad->minute_hand, ad->cx, ad->cy, minute_angle,
             ad->radius * 0.66, ambient ? 4 : 6, 205, 222, 239);

    if (ambient) {
        evas_object_hide(ad->second_hand);
    } else {
        set_hand(ad->second_hand, ad->cx, ad->cy, second_angle,
                 ad->radius * 0.78, 2, 0, 174, 255);
        evas_object_show(ad->second_hand);
    }
}

static Evas_Object *create_hand(Evas *canvas, int r, int g, int b, int width)
{
    Evas_Object *line = evas_object_line_add(canvas);
    evas_object_color_set(line, r, g, b, 255);
    evas_object_line_width_set(line, width);
    evas_object_line_xy_set(line, 0, 0, 0, 0);
    evas_object_show(line);
    return line;
}

static bool app_create(int width, int height, void *data)
{
    appdata_s *ad = data;
    Evas *canvas;
    watch_time_h wt = NULL;

    ad->width = width;
    ad->height = height;
    ad->cx = width / 2;
    ad->cy = height / 2;
    ad->radius = (width < height ? width : height) / 2 - 12;
    ad->ambient = false;

    if (watch_app_get_elm_win(&ad->win) != APP_ERROR_NONE) {
        dlog_print(DLOG_ERROR, LOG_TAG, "Cannot get watch window");
        return false;
    }

    evas_object_resize(ad->win, width, height);
    canvas = evas_object_evas_get(ad->win);

    ad->background = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->background, 3, 13, 27, 255);
    evas_object_move(ad->background, 0, 0);
    evas_object_resize(ad->background, width, height);
    evas_object_show(ad->background);

    ad->hour_hand = create_hand(canvas, 224, 235, 247, 8);
    ad->minute_hand = create_hand(canvas, 205, 222, 239, 6);
    ad->second_hand = create_hand(canvas, 0, 174, 255, 2);

    ad->hub_outer = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->hub_outer, 220, 235, 248, 255);
    evas_object_resize(ad->hub_outer, 12, 12);
    evas_object_move(ad->hub_outer, ad->cx - 6, ad->cy - 6);
    evas_object_show(ad->hub_outer);

    ad->hub_inner = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->hub_inner, 0, 174, 255, 255);
    evas_object_resize(ad->hub_inner, 5, 5);
    evas_object_move(ad->hub_inner, ad->cx - 2, ad->cy - 2);
    evas_object_show(ad->hub_inner);

    if (watch_time_get_current_time(&wt) == APP_ERROR_NONE) {
        update_watch(ad, wt, false);
        watch_time_delete(wt);
    }

    evas_object_show(ad->win);
    return true;
}

static void app_terminate(void *data) { (void)data; }
static void app_pause(void *data) { (void)data; }
static void app_resume(void *data) { (void)data; }
static void app_control(app_control_h control, void *data)
{
    (void)control;
    (void)data;
}

static void app_time_tick(watch_time_h wt, void *data)
{
    appdata_s *ad = data;
    if (ad && !ad->ambient) update_watch(ad, wt, false);
}

static void app_ambient_tick(watch_time_h wt, void *data)
{
    appdata_s *ad = data;
    if (ad) update_watch(ad, wt, true);
}

static void app_ambient_changed(bool ambient_mode, void *data)
{
    appdata_s *ad = data;
    watch_time_h wt = NULL;
    if (!ad) return;
    ad->ambient = ambient_mode;
    if (watch_time_get_current_time(&wt) == APP_ERROR_NONE) {
        update_watch(ad, wt, ambient_mode);
        watch_time_delete(wt);
    }
}

int main(int argc, char *argv[])
{
    appdata_s ad = {0,};
    watch_app_lifecycle_callback_s callbacks = {0,};

    callbacks.create = app_create;
    callbacks.terminate = app_terminate;
    callbacks.pause = app_pause;
    callbacks.resume = app_resume;
    callbacks.app_control = app_control;
    callbacks.time_tick = app_time_tick;
    callbacks.ambient_tick = app_ambient_tick;
    callbacks.ambient_changed = app_ambient_changed;

    return watch_app_main(argc, argv, &callbacks, &ad);
}
