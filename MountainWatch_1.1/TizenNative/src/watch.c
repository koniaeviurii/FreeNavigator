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
#include <sensor.h>
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
    Evas_Object *disc_gloss;
    Evas_Object *disc_glint;
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
    Evas_Object *hour_shadow, *minute_shadow;
    Evas_Object *tick_marks[60];
    Evas_Object *numerals[4];
    Evas_Object *hub_touch, *hub_outer, *hub_inner;
    sensor_h hrm_sensor, pedometer_sensor;
    sensor_listener_h hrm_listener, pedometer_listener;
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

/* Build a tapered, filled metal hand from the pivot to its tip. */
static void set_hand_polygon(Evas_Object *hand, int cx, int cy, double angle,
                             double length, int width, int r, int g, int b,
                             int offset)
{
    double a = (angle - 90.0) * PI / 180.0;
    double px = -sin(a), py = cos(a);
    double half = width / 2.0;
    double bx = cx + offset, by = cy + offset;
    double tx = bx + cos(a) * length, ty = by + sin(a) * length;
    double shoulder = length * 0.72;
    int pxs[6], pys[6], min_x, min_y, max_x, max_y, i;
    if (!hand) return;

    pxs[0] = (int)(bx + px * half);
    pys[0] = (int)(by + py * half);
    pxs[1] = (int)(bx + px * half * 0.62 + cos(a) * shoulder);
    pys[1] = (int)(by + py * half * 0.62 + sin(a) * shoulder);
    pxs[2] = (int)(tx + px);
    pys[2] = (int)(ty + py);
    pxs[3] = (int)(tx - px);
    pys[3] = (int)(ty - py);
    pxs[4] = (int)(bx - px * half * 0.62 + cos(a) * shoulder);
    pys[4] = (int)(by - py * half * 0.62 + sin(a) * shoulder);
    pxs[5] = (int)(bx - px * half);
    pys[5] = (int)(by - py * half);

    min_x = max_x = pxs[0];
    min_y = max_y = pys[0];
    for (i = 1; i < 6; i++) {
        if (pxs[i] < min_x) min_x = pxs[i];
        if (pxs[i] > max_x) max_x = pxs[i];
        if (pys[i] < min_y) min_y = pys[i];
        if (pys[i] > max_y) max_y = pys[i];
    }

    /* Evas polygon vertices are local to the object's geometry. */
    evas_object_polygon_point_clear(hand);
    for (i = 0; i < 6; i++)
        evas_object_polygon_point_add(hand, pxs[i] - min_x, pys[i] - min_y);
    evas_object_move(hand, min_x, min_y);
    evas_object_resize(hand, max_x - min_x + 1, max_y - min_y + 1);
    evas_object_color_set(hand, r, g, b, 255);
}

static void set_hand_shadow(Evas_Object *hand, int cx, int cy, double angle,
                            double length, int width)
{
    set_hand_polygon(hand, cx, cy, angle, length, width + 2, 0, 0, 0, 2);
}

static void update_watch(appdata_s *ad, watch_time_h wt, bool ambient)
{
    int h = 0, m = 0, s = 0;
    double hour_angle, minute_angle;
    if (!ad || !wt) return;
    watch_time_get_hour24(wt, &h);
    watch_time_get_minute(wt, &m);
    watch_time_get_second(wt, &s);

    hour_angle = (h % 12) * 30.0 + m * .5;
    minute_angle = m * 6.0 + s * .1;

    set_hand_shadow(ad->hour_shadow, ad->cx, ad->cy, hour_angle,
                    ad->radius * .43, ambient ? 5 : 8);
    set_hand_shadow(ad->minute_shadow, ad->cx, ad->cy, minute_angle,
                    ad->radius * .66, ambient ? 4 : 6);
    set_hand_polygon(ad->hour_hand, ad->cx, ad->cy, hour_angle,
                     ad->radius * .43, ambient ? 5 : 9, 224, 235, 247, 0);
    set_hand_polygon(ad->minute_hand, ad->cx, ad->cy, minute_angle,
                     ad->radius * .66, ambient ? 4 : 7, 205, 222, 239, 0);
    if (ambient) {
        evas_object_hide(ad->second_hand);
    } else {
        set_hand(ad->second_hand, ad->cx, ad->cy, s * 6.0,
                 ad->radius * .78, 2, 0, 174, 255);
        evas_object_show(ad->second_hand);
    }
}
static Evas_Object *create_dial_circle(Evas *canvas, int cx, int cy,
                                               int radius, int red, int green,
                                               int blue, int alpha)
{
    Evas_Object *circle;
    int i;
    if (!canvas || radius <= 0) return NULL;
    circle = evas_object_polygon_add(canvas);
    for (i = 0; i < 72; i++) {
        double a = 2.0 * PI * i / 72.0;
        evas_object_polygon_point_add(circle,
            radius + (int)(cos(a) * radius),
            radius + (int)(sin(a) * radius));
    }
    evas_object_color_set(circle, red, green, blue, alpha);
    evas_object_move(circle, cx - radius, cy - radius);
    evas_object_show(circle);
    return circle;
}

static void create_dial_bezel(appdata_s *ad, Evas *canvas)
{
    if (!ad || !canvas) return;
    /* Layered concentric tones create a restrained brushed-steel bezel. */
    create_dial_circle(canvas, ad->cx, ad->cy, ad->radius + 2,
                       3, 5, 9, 255);
    create_dial_circle(canvas, ad->cx, ad->cy, ad->radius,
                       174, 190, 207, 255);
    create_dial_circle(canvas, ad->cx, ad->cy, ad->radius - 4,
                       72, 91, 112, 255);
    create_dial_circle(canvas, ad->cx, ad->cy, ad->radius - 9,
                       5, 17, 33, 255);
}

static void create_dial_marks(appdata_s *ad, Evas *canvas)
{
    int i;
    const char *numbers[4] = {"12", "3", "6", "9"};
    int nx[4], ny[4];
    if (!ad || !canvas) return;

    /* Fixed silver minute/hour marks around the dial; never draggable. */
    for (i = 0; i < 60; i++) {
        double a = (i * 6.0 - 90.0) * PI / 180.0;
        double inner = ad->radius * (i % 5 == 0 ? 0.88 : 0.93);
        double outer = ad->radius * 0.97;
        Evas_Object *line = evas_object_line_add(canvas);
        evas_object_line_xy_set(line,
            ad->cx + (int)(cos(a) * inner),
            ad->cy + (int)(sin(a) * inner),
            ad->cx + (int)(cos(a) * outer),
            ad->cy + (int)(sin(a) * outer));
        evas_object_line_width_set(line, i % 5 == 0 ? 3 : 1);
        evas_object_color_set(line, 190, 207, 225, i % 5 == 0 ? 255 : 175);
        evas_object_show(line);
        ad->tick_marks[i] = line;
    }

    nx[0] = ad->cx; ny[0] = ad->cy - (int)(ad->radius * 0.73);
    nx[1] = ad->cx + (int)(ad->radius * 0.73); ny[1] = ad->cy;
    nx[2] = ad->cx; ny[2] = ad->cy + (int)(ad->radius * 0.73);
    nx[3] = ad->cx - (int)(ad->radius * 0.73); ny[3] = ad->cy;
    for (i = 0; i < 4; i++) {
        Evas_Object *label = elm_label_add(ad->win);
        elm_object_text_set(label, numbers[i]);
        evas_object_color_set(label, 220, 230, 242, 255);
        evas_object_resize(label, 42, 34);
        evas_object_move(label, nx[i] - 21, ny[i] - 17);
        evas_object_show(label);
        ad->numerals[i] = label;
    }
}

static Evas_Object *create_hand(Evas *canvas)
{
    Evas_Object *o = evas_object_polygon_add(canvas);
    evas_object_color_set(o, 220, 230, 242, 255);
    evas_object_show(o);
    return o;
}

static Evas_Object *create_second_hand(Evas *canvas)
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
    if (w->disc_gloss) evas_object_move(w->disc_gloss, x - (int)(w->radius * .62), y - (int)(w->radius * .72));
    if (w->disc_glint) evas_object_move(w->disc_glint, x - (int)(w->radius * .48), y - (int)(w->radius * .78));
    evas_object_move(w->label, x - w->radius, y - 18);
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
    double dx, dy, distance;
    (void)e; (void)obj;
    if (!w || !ev || !w->dragging) return;
    w->moved = true;
    x = ev->cur.output.x + w->drag_dx;
    y = ev->cur.output.y + w->drag_dy;
    maxr = (w->app->width < w->app->height ?
            w->app->width : w->app->height) / 2 - w->radius - 8;

    /* Keep the whole indicator inside the round dial, not a square boundary. */
    dx = x - w->app->cx;
    dy = y - w->app->cy;
    distance = sqrt(dx * dx + dy * dy);
    if (distance > maxr && distance > 0.0) {
        x = w->app->cx + (int)(dx * maxr / distance);
        y = w->app->cy + (int)(dy * maxr / distance);
    }
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

    /* A small translucent highlight adds a glass-like raised indicator finish. */
    w->disc_gloss = evas_object_polygon_add(canvas);
    for (i = 0; i < WIDGET_POINTS; i++) {
        double a = 2.0 * PI * i / WIDGET_POINTS;
        int gloss_r = (int)(radius * .48);
        evas_object_polygon_point_add(w->disc_gloss,
            gloss_r + (int)(cos(a) * gloss_r),
            gloss_r + (int)(sin(a) * gloss_r));
    }
    evas_object_color_set(w->disc_gloss, 255, 255, 255, 58);
    evas_object_move(w->disc_gloss,
        x - (int)(radius * .62), y - (int)(radius * .72));
    evas_object_show(w->disc_gloss);
    evas_object_pass_events_set(w->disc_gloss, EINA_TRUE);

    /* Tiny specular glint at the upper edge, part of the movable widget. */
    w->disc_glint = evas_object_polygon_add(canvas);
    for (i = 0; i < WIDGET_POINTS; i++) {
        double a = 2.0 * PI * i / WIDGET_POINTS;
        int glint_rx = (int)(radius * .13);
        int glint_ry = (int)(radius * .055);
        evas_object_polygon_point_add(w->disc_glint,
            glint_rx + (int)(cos(a) * glint_rx),
            glint_ry + (int)(sin(a) * glint_ry));
    }
    evas_object_color_set(w->disc_glint, 255, 255, 255, 105);
    evas_object_move(w->disc_glint,
        x - (int)(radius * .48), y - (int)(radius * .78));
    evas_object_show(w->disc_glint);
    evas_object_pass_events_set(w->disc_glint, EINA_TRUE);

    w->label = elm_label_add(ad->win);
    elm_object_text_set(w->label, caption);
    evas_object_color_set(w->label, 255, 255, 255, 255);
    evas_object_resize(w->label, radius * 2, 40);
    evas_object_move(w->label, x - radius, y - 18);
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
    snprintf(text, sizeof(text), "BATTERY\n%d%%", percent);
    elm_object_text_set(w->label, text);
}

static void update_sensor_label(appdata_s *ad, int index, const char *name, int value)
{
    char text[48];
    if (!ad || index < 0 || index >= WIDGET_COUNT || !ad->widgets[index].label) return;
    snprintf(text, sizeof(text), "%s\n%d", name, value);
    elm_object_text_set(ad->widgets[index].label, text);
}

static void hrm_event(sensor_h sensor, sensor_event_s *event, void *data)
{
    appdata_s *ad = data;
    (void)sensor;
    if (!event || event->value_count < 1) return;
    if (event->values[0] > 0 && event->values[0] < 250)
        update_sensor_label(ad, 1, "PULSE", (int)(event->values[0] + 0.5f));
}

static void pedometer_event(sensor_h sensor, sensor_event_s *event, void *data)
{
    appdata_s *ad = data;
    int steps;
    char distance_text[48];
    (void)sensor;
    if (!event || event->value_count < 1 || event->values[0] < 0) return;

    steps = (int)(event->values[0] + 0.5f);
    update_sensor_label(ad, 0, "STEPS", steps);

    /*
     * Provisional estimate only: 0.70 m per step.
     * This is not GPS distance and must be user-calibrated before release.
     */
    snprintf(distance_text, sizeof(distance_text), "DISTANCE\n~%.2f km",
             (steps * 0.70f) / 1000.0f);
    if (ad && ad->widgets[3].label)
        elm_object_text_set(ad->widgets[3].label, distance_text);
}

static void start_health_sensors(appdata_s *ad)
{
    bool supported = false;
    if (!ad) return;

    if (!ad->hrm_listener &&
        sensor_is_supported(SENSOR_HRM, &supported) == SENSOR_ERROR_NONE && supported &&
        sensor_get_default_sensor(SENSOR_HRM, &ad->hrm_sensor) == SENSOR_ERROR_NONE &&
        sensor_create_listener(ad->hrm_sensor, &ad->hrm_listener) == SENSOR_ERROR_NONE) {
        if (sensor_listener_set_event_cb(ad->hrm_listener, 1000, hrm_event, ad) != SENSOR_ERROR_NONE ||
            sensor_listener_start(ad->hrm_listener) != SENSOR_ERROR_NONE) {
            dlog_print(DLOG_WARN, LOG_TAG, "Heart-rate sensor could not be started");
            sensor_destroy_listener(ad->hrm_listener);
            ad->hrm_listener = NULL;
        }
    }

    supported = false;
    if (!ad->pedometer_listener &&
        sensor_is_supported(SENSOR_HUMAN_PEDOMETER, &supported) == SENSOR_ERROR_NONE && supported &&
        sensor_get_default_sensor(SENSOR_HUMAN_PEDOMETER, &ad->pedometer_sensor) == SENSOR_ERROR_NONE &&
        sensor_create_listener(ad->pedometer_sensor, &ad->pedometer_listener) == SENSOR_ERROR_NONE) {
        if (sensor_listener_set_event_cb(ad->pedometer_listener, 1000, pedometer_event, ad) != SENSOR_ERROR_NONE ||
            sensor_listener_start(ad->pedometer_listener) != SENSOR_ERROR_NONE) {
            dlog_print(DLOG_WARN, LOG_TAG, "Pedometer sensor could not be started");
            sensor_destroy_listener(ad->pedometer_listener);
            ad->pedometer_listener = NULL;
        }
    }
}

static void stop_health_sensors(appdata_s *ad)
{
    if (!ad) return;
    if (ad->hrm_listener) {
        sensor_listener_stop(ad->hrm_listener);
        sensor_destroy_listener(ad->hrm_listener);
        ad->hrm_listener = NULL;
    }
    if (ad->pedometer_listener) {
        sensor_listener_stop(ad->pedometer_listener);
        sensor_destroy_listener(ad->pedometer_listener);
        ad->pedometer_listener = NULL;
    }
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

    create_dial_bezel(ad, canvas);
    create_dial_marks(ad, canvas);

    ad->hour_shadow = create_hand(canvas);
    ad->minute_shadow = create_hand(canvas);
    ad->hour_hand = create_hand(canvas);
    ad->minute_hand = create_hand(canvas);
    ad->second_hand = create_second_hand(canvas);

    /* Four separate touch targets; each has its own position and base color. */
    create_widget(ad, canvas, 0, ad->cx - ad->radius * .53,
        ad->cy - ad->radius * .43, ad->radius * .16, 18, 151, 225, "STEPS");
    create_widget(ad, canvas, 1, ad->cx + ad->radius * .53,
        ad->cy - ad->radius * .43, ad->radius * .16, 226, 58, 78, "PULSE");
    create_widget(ad, canvas, 2, ad->cx - ad->radius * .42,
        ad->cy + ad->radius * .48, ad->radius * .13, 70, 205, 104, "BATTERY");
    create_widget(ad, canvas, 3, ad->cx + ad->radius * .42,
        ad->cy + ad->radius * .48, ad->radius * .13, 18, 174, 220, "DISTANCE");

    /* Start live health readings when the watch exposes the sensors. */
    start_health_sensors(ad);

    /* Keep the center reset action easy to hit on the small watch display. */
    ad->hub_touch = evas_object_rectangle_add(canvas);
    evas_object_color_set(ad->hub_touch, 0, 0, 0, 1);
    evas_object_resize(ad->hub_touch, 40, 40);
    evas_object_move(ad->hub_touch, ad->cx - 20, ad->cy - 20);
    evas_object_show(ad->hub_touch);
    evas_object_event_callback_add(ad->hub_touch, EVAS_CALLBACK_MOUSE_UP,
                                   reset_widget_positions, ad);

    /* Keep the analog hands visually above the four indicator discs. */
    evas_object_raise(ad->hour_shadow);
    evas_object_raise(ad->minute_shadow);
    evas_object_raise(ad->hour_hand);
    evas_object_raise(ad->minute_hand);
    evas_object_raise(ad->second_hand);

    ad->hub_outer = create_dial_circle(canvas, ad->cx, ad->cy, 7,
                                       220, 235, 248, 255);
    /* Tap the circular central hub to restore all four indicators. */
    evas_object_event_callback_add(ad->hub_outer, EVAS_CALLBACK_MOUSE_UP,
                                   reset_widget_positions, ad);
    ad->hub_inner = create_dial_circle(canvas, ad->cx, ad->cy, 3,
                                       0, 174, 255, 255);
    evas_object_pass_events_set(ad->hub_inner, EINA_TRUE);

    refresh_battery_widget(ad);

    if (watch_time_get_current_time(&wt) == APP_ERROR_NONE) {
        update_watch(ad, wt, false);
        watch_time_delete(wt);
    }
    evas_object_show(ad->win);
    return true;
}

static void app_terminate(void *data) { stop_health_sensors((appdata_s *)data); }
static void app_pause(void *data) { stop_health_sensors((appdata_s *)data); }
static void app_resume(void *data) { start_health_sensors((appdata_s *)data); }
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
    /* Pause continuous health sampling in low-power ambient mode. */
    if (ambient_mode) stop_health_sensors(ad);
    else start_health_sensors(ad);
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
