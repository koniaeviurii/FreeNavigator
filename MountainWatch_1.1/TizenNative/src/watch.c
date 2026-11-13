/*
 * Mountain Watch 1.1 — native Tizen 4.0 watch application
 * Target: Samsung Galaxy Watch SM-R800.
 * Current native prototype: analog clock and four independently draggable
 * circular indicators. Approved photo artwork and sensor data are pending.
 */
#include <watch_app.h>
#include <app.h>
#include <app_common.h>
#include <Elementary.h>
#include <dlog.h>
#include <math.h>
#include <device/battery.h>
#include <stdbool.h>
#include <stdio.h>

#define LOG_TAG "MOUNTAIN_WATCH"
#define PI 3.14159265358979323846
#define WIDGET_COUNT 4
#define WIDGET_POINTS 48

typedef struct appdata_s appdata_s;
typedef struct {
    appdata_s *app;
    Evas_Object *disc;
    Evas_Object *label;
    int x, y, radius;
    int red, green, blue;
    int drag_dx, drag_dy;
    bool dragging;
    bool moved;
    int color_index;
    const char *caption;
} widget_s;

struct appdata_s {
    Evas_Object *win, *background;
    Evas_Object *hour_hand, *minute_hand, *second_hand;
    Evas_Object *hub_touch, *hub_outer, *hub_inner;
    int width, height, cx, cy, radius;
    bool ambient;
    widget_s widgets[WIDGET_COUNT];
};

static void set_hand(Evas_Object *line, int cx, int cy, double angle,
                     double length, int width, int r, int g, int b)
{
    double a = (angle - 90.0) * PI / 180.0;
    evas_object_line_xy_set(line, cx, cy,
        cx + (int)(cos(a) * length), cy + (int)(sin(a) * length));
    evas_object_line_width_set(line, width);
    evas_object_color_set(line, r, g, b, 255);
}

static void update_watch(appdata_s *ad, watch_time_h wt, bool ambient)
{
    int h = 0, m = 0, s = 0;
    if (!ad || !wt) return;
    watch_time_get_hour24(wt, &h);
    watch_time_get_minute(wt, &m);
    watch_time_get_second(wt, &s);

    set_hand(ad->hour_hand, ad->cx, ad->cy, (h % 12) * 30.0 + m * .5,
             ad->radius * .43, ambient ? 5 : 8, 224, 235, 247);
    set_hand(ad->minute_hand, ad->cx, ad->cy, m * 6.0 + s * .1,
             ad->radius * .66, ambient ? 4 : 6, 205, 222, 239);
    if (ambient) {
        evas_object_hide(ad->second_hand);
    } else {
        set_hand(ad->second_hand, ad->cx, ad->cy, s * 6.0,
                 ad->radius * .78, 2, 0, 174, 255);
        evas_object_show(ad->second_hand);
    }
}

static Evas_Object *create_hand(Evas *canvas)
{
    Evas_Object *o = evas_object_line_add(canvas);
    evas_object_line_xy_set(o, 0, 0, 0, 0);
    evas_object_show(o);
    return o;
}

static void widget_apply_color(widget_s *w)
{
    static const int palette[5][3] = {
        {18, 151, 225}, {226, 58, 78}, {70, 205, 104},
        {18, 174, 220}, {218, 166, 68}
    };
    int p;
    if (!w) return;
    p = w->color_index % 5;
    w->red = palette[p][0];
    w->green = palette[p][1];
    w->blue = palette[p][2];
    evas_object_color_set(w->disc, w->red, w->green, w->blue, 220);
}

static void widget_save_color(widget_s *w)
{
    char key[40];
    if (!w || !w->app) return;
    snprintf(key, sizeof(key), "widget_%d_color", (int)(w - w->app->widgets));
    preference_set_int(key, w->color_index);
}

static void widget_save_position(widget_s *w)
{
    char key[40];
    if (!w || !w->app) return;
    snprintf(key, sizeof(key), "widget_%d_x", (int)(w - w->app->widgets));
    preference_set_int(key, w->x);
    snprintf(key, sizeof(key), "widget_%d_y", (int)(w - w->app->widgets));
    preference_set_int(key, w->y);
}

static void widget_load_position(widget_s *w)
{
    char key[40];
    int value;
    if (!w || !w->app) return;
    snprintf(key, sizeof(key), "widget_%d_x", (int)(w - w->app->widgets));
    if (preference_get_int(key, &value) == APP_ERROR_NONE) w->x = value;
    snprintf(key, sizeof(key), "widget_%d_y", (int)(w - w->app->widgets));
    if (preference_get_int(key, &value) == APP_ERROR_NONE) w->y = value;
}

static void widget_move(widget_s *w, int x, int y)
{
    evas_object_move(w->disc, x - w->radius, y - w->radius);
    evas_object_move(w->label, x - w->radius, y - 10);
    /* The polygon is built in local coordinates; moving its object moves it. */
    w->x = x;
    w->y = y;
}

static void widget_down(void *data, Evas *e, Evas_Object *obj, void *event)
{
    widget_s *w = data;
    Evas_Event_Mouse_Down *ev = event;
    (void)e; (void)obj;
    if (!w || !ev) return;
    w->drag_dx = w->x - ev->output.x;
    w->drag_dy = w->y - ev->output.y;
    w->dragging = true;
    w->moved = false;
}

static void widget_move_event(void *data, Evas *e, Evas_Object *obj, void *event)
{
    widget_s *w = data;
    Evas_Event_Mouse_Move *ev = event;
    int x, y, maxr;
    (void)e; (void)obj;
    if (!w || !ev || !w->dragging) return;
    w->moved = true;
    x = ev->cur.output.x + w->drag_dx;
    y = ev->cur.output.y + w->drag_dy;
    maxr = (w->app->width < w->app->height ?
            w->app->width : w->app->height) / 2 - w->radius - 8;
    if (x < w->app->cx - maxr) x = w->app->cx - maxr;
    if (x > w->app->cx + maxr) x = w->app->cx + maxr;
    if (y < w->app->cy - maxr) y = w->app->cy - maxr;
    if (y > w->app->cy + maxr) y = w->app->cy + maxr;
    widget_move(w, x, y);
}

static void widget_up(void *data, Evas *e, Evas_Object *obj, void *event)
{
    widget_s *w = data;
    (void)e; (void)obj; (void)event;
    if (w) {
        w->dragging = false;
        if (w->moved) {
            widget_save_position(w);
        } else {
            w->color_index = (w->color_index + 1) % 5;
            widget_apply_color(w);
            widget_save_color(w);
        }
    }
}

static void reset_widget_positions(void *data, Evas *e, Evas_Object *obj, void *event)
{
    appdata_s *ad = data;
    int i;
    (void)e; (void)obj; (void)event;
    if (!ad) return;
    for (i = 0; i < WIDGET_COUNT; i++) {
        widget_s *w = &ad->widgets[i];
        int x, y;
        if (i == 0) { x = ad->cx - ad->radius * .53; y = ad->cy - ad->radius * .43; }
        else if (i == 1) { x = ad->cx + ad->radius * .53; y = ad->cy - ad->radius * .43; }
        else if (i == 2) { x = ad->cx - ad->radius * .42; y = ad->cy + ad->radius * .48; }
        else { x = ad->cx + ad->radius * .42; y = ad->cy + ad->radius * .48; }
        widget_move(w, x, y);
        widget_save_position(w);
    }
}

static void create_widget(appdata_s *ad, Evas *canvas, int index,
                          int x, int y, int radius, int red, int green,
                          int blue, const char *caption)
{
    widget_s *w = &ad->widgets[index];
    int i;
    w->app = ad;
    w->x = x; w->y = y; w->radius = radius;
    w->red = red; w->green = green; w->blue = blue;
    w->color_index = index;
    w->caption = caption;
    widget_load_position(w);
    {
        char key[40];
        int saved_color;
        snprintf(key, sizeof(key), "widget_%d_color", index);
        if (preference_get_int(key, &saved_color) == APP_ERROR_NONE &&
            saved_color >= 0 && saved_color < 5) w->color_index = saved_color;
    }
    w->disc = evas_object_polygon_add(canvas);
    evas_object_color_set(w->disc, red, green, blue, 220);
    for (i = 0; i < WIDGET_POINTS; i++) {
        double a = 2.0 * PI * i / WIDGET_POINTS;
        evas_object_polygon_point_add(w->disc,
            radius + (int)(cos(a) * radius),
            radius + (int)(sin(a) * radius));
    }
    evas_object_move(w->disc, x - radius, y - radius);
    evas_object_show(w->disc);
    widget_apply_color(w);

    w->label = elm_label_add(ad->win);
    elm_object_text_set(w->label, caption);
    evas_object_color_set(w->label, 255, 255, 255, 255);
    evas_object_resize(w->label, radius * 2, 24);
    evas_object_move(w->label, x - radius, y - 10);
    evas_object_show(w->label);
    /* Let touches pass through the text to the draggable circular target. */
    evas_object_pass_events_set(w->label, EINA_TRUE);

    evas_object_event_callback_add(w->disc, EVAS_CALLBACK_MOUSE_DOWN, widget_down, w);
    evas_object_event_callback_add(w->disc, EVAS_CALLBACK_MOUSE_MOVE, widget_move_event, w);
    evas_object_event_callback_add(w->disc, EVAS_CALLBACK_MOUSE_UP, widget_up, w);
}

static void refresh_battery_widget(appdata_s *ad)
{
    int percent = -1;
    char text[48];
    widget_s *w;
    if (!ad) return;
    if (device_battery_get_percent(&percent) != DEVICE_ERROR_NONE ||
        percent < 0 || percent > 100) return;
    w = &ad->widgets[2];
    if (!w->label) return;
    snprintf(text, sizeof(text), "BATTERY\\n%d%%", percent);
    elm_object_text_set(w->label, text);
}

static bool app_create(int width, int height, void *data)
{
    appdata_s *ad = data;
    Evas *canvas;
    watch_time_h wt = NULL;

    ad->width = width; ad->height = height;
    ad->cx = width / 2; ad->cy = height / 2;
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

    ad->hour_hand = create_hand(canvas);
    ad->minute_hand = create_hand(canvas);
    ad->second_hand = create_hand(canvas);

    /* Four separate touch targets; each has its own position and base color. */
    create_widget(ad, canvas, 0, ad->cx - ad->radius * .53,
        ad->cy - ad->radius * .43, ad->radius * .16, 18, 151, 225, "STEPS");
    create_widget(ad, canvas, 1, ad->cx + ad->radius * .53,
        ad->cy - ad->radius * .43, ad->radius * .16, 226, 58, 78, "PULSE");
    create_widget(ad, canvas, 2, ad->cx - ad->radius * .42,
        ad->cy + ad->radius * .48, ad->radius * .13, 70, 205, 104, "BATTERY");
    create_widget(ad, canvas, 3, ad->cx + ad->radius * .42,
        ad->cy + ad->radius * .48, ad->radius * .13, 18, 174, 220, "DISTANCE");

    /* Keep the center reset action easy to hit on the small watch display. */
    ad->hub_touch = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->hub_touch, 0, 0, 0, 1);
    evas_object_resize(ad->hub_touch, 40, 40);
    evas_object_move(ad->hub_touch, ad->cx - 20, ad->cy - 20);
    evas_object_show(ad->hub_touch);
    evas_object_event_callback_add(ad->hub_touch, EVAS_CALLBACK_MOUSE_UP,
                                   reset_widget_positions, ad);

    /* Keep the analog hands visually above the four indicator discs. */
    evas_object_raise(ad->hour_hand);
    evas_object_raise(ad->minute_hand);
    evas_object_raise(ad->second_hand);

    ad->hub_outer = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->hub_outer, 220, 235, 248, 255);
    evas_object_resize(ad->hub_outer, 12, 12);
    evas_object_move(ad->hub_outer, ad->cx - 6, ad->cy - 6);
    evas_object_show(ad->hub_outer);
    /* Tap the central hub to restore all four indicators to their defaults. */
    evas_object_event_callback_add(ad->hub_outer, EVAS_CALLBACK_MOUSE_UP,
                                   reset_widget_positions, ad);
    ad->hub_inner = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->hub_inner, 0, 174, 255, 255);
    evas_object_resize(ad->hub_inner, 5, 5);
    evas_object_move(ad->hub_inner, ad->cx - 2, ad->cy - 2);
    evas_object_show(ad->hub_inner);
    evas_object_pass_events_set(ad->hub_inner, EINA_TRUE);

    refresh_battery_widget(ad);

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
{ (void)control; (void)data; }

static void app_time_tick(watch_time_h wt, void *data)
{
    appdata_s *ad = data;
    if (ad && !ad->ambient) {
        update_watch(ad, wt, false);
        refresh_battery_widget(ad);
    }
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
