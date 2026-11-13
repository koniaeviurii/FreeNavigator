/*
 * Mountain Watch 1.1 — native Tizen watch application foundation
 * Target: Samsung Galaxy Watch SM-R800 / Tizen 4.0
 *
 * The approved photographic dial and separate editable widget layers
 * will be integrated in the next implementation stage. This file is
 * the native watch lifecycle/timekeeping base, not the finished face.
 */
#include <watch_app.h>
#include <app.h>
#include <Elementary.h>
#include <dlog.h>
#include <time.h>
#include <stdio.h>

#define LOG_TAG "MOUNTAIN_WATCH"
#define TEXT_BUF_SIZE 64

typedef struct {
    Evas_Object *win;
    Evas_Object *background;
    Evas_Object *time_label;
    int width;
    int height;
    bool ambient;
} appdata_s;

static void update_watch(appdata_s *ad, watch_time_h wt, bool ambient)
{
    int hour = 0, minute = 0, second = 0;
    char text[TEXT_BUF_SIZE];

    if (!ad || !wt || !ad->time_label) return;

    watch_time_get_hour24(wt, &hour);
    watch_time_get_minute(wt, &minute);
    watch_time_get_second(wt, &second);

    if (ambient)
        snprintf(text, sizeof(text), "%02d:%02d", hour, minute);
    else
        snprintf(text, sizeof(text), "%02d:%02d:%02d", hour, minute, second);

    elm_object_text_set(ad->time_label, text);
}

static bool app_create(int width, int height, void *data)
{
    appdata_s *ad = data;
    watch_time_h wt = NULL;

    ad->width = width;
    ad->height = height;
    ad->ambient = false;

    if (watch_app_get_elm_win(&ad->win) != APP_ERROR_NONE) {
        dlog_print(DLOG_ERROR, LOG_TAG, "Cannot get watch window");
        return false;
    }

    evas_object_resize(ad->win, width, height);

    ad->background = evas_object_rectangle_add(evas_object_evas_get(ad->win));
    evas_object_color_set(ad->background, 4, 12, 24, 255);
    evas_object_move(ad->background, 0, 0);
    evas_object_resize(ad->background, width, height);
    evas_object_show(ad->background);

    ad->time_label = elm_label_add(ad->win);
    elm_object_text_set(ad->time_label, "00:00");
    evas_object_color_set(ad->time_label, 245, 249, 255, 255);
    evas_object_resize(ad->time_label, width, 90);
    evas_object_move(ad->time_label, 0, height / 2 - 45);
    evas_object_show(ad->time_label);

    if (watch_time_get_current_time(&wt) == APP_ERROR_NONE) {
        update_watch(ad, wt, false);
        watch_time_delete(wt);
    }

    evas_object_show(ad->win);
    return true;
}

static void app_terminate(void *data)
{
    (void)data;
}

static void app_pause(void *data)
{
    (void)data;
}

static void app_resume(void *data)
{
    (void)data;
}

static void app_control(app_control_h app_control, void *data)
{
    (void)app_control;
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
    if (ad) {
        ad->ambient = ambient_mode;
        watch_time_h wt = NULL;
        if (watch_time_get_current_time(&wt) == APP_ERROR_NONE) {
            update_watch(ad, wt, ambient_mode);
            watch_time_delete(wt);
        }
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
