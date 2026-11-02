/*
 * OpenBachelorC - JNI bridge over libfrida-core.a
 *
 * Mirrors the behaviour of the original Python launcher:
 *   adb.py / inject.py  ->  obc_* functions below
 *
 * The client runs on the same device as the game, so no adb port forwarding
 * is needed: the Gadget listens on 127.0.0.1:10443 (see OpenBachelorG/main.py).
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#include "frida-core.h"

#define LOG_TAG "ObcNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_SCRIPTS 8

/*
 * The agents print everything they do through console.log/console.error, and
 * they never call send(). Without a bridge those messages stay inside the
 * target process, so a silent failure (bad config key, agent never reaching
 * its init, an exception mid-injection) is indistinguishable from success.
 *
 * This shim is prepended to every agent and posts console output back to the
 * host as {"type":"console", ...}; a top-level try/catch turns a load-time
 * throw into an observable "exception" message.
 */
static const char *CONSOLE_SHIM =
    ";(function(){\n"
    "  var __obcSend = globalThis.send;\n"
    "  if (typeof __obcSend !== 'function') return;\n"
    "  var __obcFmt = function(a){\n"
    "    if (typeof a === 'string') return a;\n"
    "    try { return JSON.stringify(a); } catch (e) { return String(a); }\n"
    "  };\n"
    "  ['log','info','warn','error','debug'].forEach(function(level){\n"
    "    var original = globalThis.console[level];\n"
    "    globalThis.console[level] = function(){\n"
    "      var parts = [];\n"
    "      for (var i = 0; i < arguments.length; i++) parts.push(__obcFmt(arguments[i]));\n"
    "      try { __obcSend({ type: 'console', level: level, text: parts.join(' ') }); } catch (e) {}\n"
    "      if (original) try { original.apply(globalThis.console, arguments); } catch (e) {}\n"
    "    };\n"
    "  });\n"
    "})();\n"
    "try {\n";

/* Closes the try opened by CONSOLE_SHIM and reports a load-time throw. */
static const char *EPILOGUE =
    "\n} catch (e) {\n"
    "  try { globalThis.send({ type: 'exception', text: String(e && e.stack || e) }); } catch (x) {}\n"
    "  throw e;\n"
    "}\n";

typedef struct {
    char *name;
    FridaScript *script;
} ScriptSlot;

static JavaVM *g_vm = NULL;
static jobject g_listener = NULL;          /* global ref to Kotlin listener */
static jmethodID g_on_message = NULL;

static FridaDeviceManager *g_manager = NULL;
static FridaDevice *g_device = NULL;
static FridaSession *g_session = NULL;
static guint g_pid = 0;
static ScriptSlot g_scripts[MAX_SCRIPTS];
static int g_script_count = 0;
static GMutex g_lock;

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

static JNIEnv *get_env(int *needs_detach)
{
    JNIEnv *env = NULL;
    *needs_detach = 0;

    if (g_vm == NULL)
        return NULL;

    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK)
            return NULL;
        *needs_detach = 1;
    } else if (rc != JNI_OK) {
        return NULL;
    }

    return env;
}

static void emit(const char *name, const char *text)
{
    int detach = 0;
    JNIEnv *env = get_env(&detach);
    if (env == NULL || g_listener == NULL || g_on_message == NULL)
        return;

    jstring jname = (*env)->NewStringUTF(env, name != NULL ? name : "");
    jstring jtext = (*env)->NewStringUTF(env, text != NULL ? text : "");
    (*env)->CallVoidMethod(env, g_listener, g_on_message, jname, jtext);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jtext);

    if (detach)
        (*g_vm)->DetachCurrentThread(g_vm);
}

static const char *err_text(GError *error)
{
    if (error == NULL)
        return "(no error detail)";
    return error->message;
}

/* FridaScript emits the GObject signal "message" with (json, data). */
static void on_script_message(FridaScript *script,
                              const gchar *json,
                              GBytes *data,
                              gpointer user_data)
{
    const char *name = (const char *) user_data;
    emit(name, json != NULL ? json : "{}");
}

/* Returns the slot's own name pointer, which stays valid for the lifetime of
 * the slot. It is used as the signal handler's user_data, so it must NOT be a
 * JNI local reference (those die with the calling frame). */
static const char *script_slot_add(const char *name, FridaScript *script)
{
    int i;

    for (i = 0; i < g_script_count; i++) {
        if (g_scripts[i].name != NULL && strcmp(g_scripts[i].name, name) == 0) {
            if (g_scripts[i].script != NULL)
                frida_unref(g_scripts[i].script);
            g_scripts[i].script = script;
            return g_scripts[i].name;
        }
    }

    if (g_script_count < MAX_SCRIPTS) {
        g_scripts[g_script_count].name = strdup(name);
        g_scripts[g_script_count].script = script;
        g_script_count++;
        return g_scripts[g_script_count - 1].name;
    }

    LOGW("script slot table full, %s not tracked", name);
    /* Still hand back something stable-ish: leak-free fallback is the caller's
     * problem, so keep the script untracked but usable via its own name. */
    return NULL;
}

static FridaScript *script_slot_get(const char *name)
{
    int i;

    for (i = 0; i < g_script_count; i++) {
        if (g_scripts[i].name != NULL && strcmp(g_scripts[i].name, name) == 0)
            return g_scripts[i].script;
    }

    return NULL;
}

/* ------------------------------------------------------------------ */
/* JNI: lifecycle                                                      */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeInit(JNIEnv *env, jobject thiz,
                                                     jobject listener)
{
    (void) thiz;

    (*env)->GetJavaVM(env, &g_vm);

    if (g_listener != NULL)
        (*env)->DeleteGlobalRef(env, g_listener);
    g_listener = (*env)->NewGlobalRef(env, listener);

    jclass cls = (*env)->GetObjectClass(env, listener);
    g_on_message = (*env)->GetMethodID(env, cls, "onNativeMessage",
                                       "(Ljava/lang/String;Ljava/lang/String;)V");
    if (g_on_message == NULL) {
        (*env)->ExceptionClear(env);
        LOGE("listener is missing onNativeMessage(String, String)");
    }
    (*env)->DeleteLocalRef(env, cls);

    g_mutex_init(&g_lock);

    frida_init();
    LOGI("frida %s initialised", frida_version_string());
}

JNIEXPORT void JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeDeinit(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    (void) env;

    g_mutex_lock(&g_lock);

    for (int i = 0; i < g_script_count; i++) {
        if (g_scripts[i].script != NULL)
            frida_unref(g_scripts[i].script);
        free(g_scripts[i].name);
        g_scripts[i].script = NULL;
        g_scripts[i].name = NULL;
    }
    g_script_count = 0;

    if (g_session != NULL) {
        frida_unref(g_session);
        g_session = NULL;
    }
    if (g_device != NULL) {
        frida_unref(g_device);
        g_device = NULL;
    }
    if (g_manager != NULL) {
        frida_unref(g_manager);
        g_manager = NULL;
    }

    g_mutex_unlock(&g_lock);

    frida_deinit();
    LOGI("frida deinitialised");
}

/* ------------------------------------------------------------------ */
/* JNI: connect / attach                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeVersion(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    return (*env)->NewStringUTF(env, frida_version_string());
}

/*
 * Connects to a remote frida endpoint (the Gadget's "listen" interface).
 * Equivalent of `frida.get_remote_device()` in the Python client.
 */
JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeConnect(JNIEnv *env, jobject thiz,
                                                        jstring jhost, jint port)
{
    (void) thiz;

    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    gchar *address = g_strdup_printf("%s:%d", host, port);
    GError *error = NULL;
    char buf[512];

    g_mutex_lock(&g_lock);

    if (g_device != NULL) {
        frida_unref(g_device);
        g_device = NULL;
    }

    if (g_manager == NULL) {
        g_manager = frida_device_manager_new();
        if (g_manager == NULL) {
            g_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, jhost, host);
            return (*env)->NewStringUTF(env, "err: could not create device manager");
        }
    }

    /*
     * Two ways into an endpoint that speaks the frida-server protocol:
     *   - add_remote_device: registers an explicit host:port (what we want for
     *     the Gadget's listen interface)
     *   - find_device_by_type(REMOTE): asks the manager to probe the usual
     *     remote candidates
     * We try the explicit one first and fall back to the probe.
     */
    FridaDevice *added = frida_device_manager_add_remote_device_sync(g_manager, address,
                                                                    NULL, NULL, &error);
    if (error == NULL && added != NULL) {
        gchar *device_id = g_strdup(frida_device_get_id(added));
        frida_unref(added);
        g_device = frida_device_manager_find_device_by_id_sync(g_manager, device_id,
                                                             0, NULL, &error);
        g_free(device_id);
    }

    if (g_device == NULL) {
        if (error != NULL) {
            LOGI("explicit remote device failed (%s), probing REMOTE type", err_text(error));
            g_error_free(error);
            error = NULL;
        }
        g_device = frida_device_manager_find_device_by_type_sync(
                g_manager, FRIDA_DEVICE_TYPE_REMOTE, 5000, NULL, &error);
    }

    if (error != NULL || g_device == NULL) {
        snprintf(buf, sizeof(buf), "err: connect %s failed: %s", address,
                 error != NULL ? err_text(error) : "no device returned");
        if (error != NULL)
            g_error_free(error);
        g_free(address);
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jhost, host);
        return (*env)->NewStringUTF(env, buf);
    }

    snprintf(buf, sizeof(buf), "connected to %s", address);
    LOGI("%s", buf);

    g_free(address);
    g_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jhost, host);

    return (*env)->NewStringUTF(env, buf);
}

/*
 * Attaches to the Gadget process.
 * Equivalent of the Python `device.attach("Gadget")`.
 */
JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeAttach(JNIEnv *env, jobject thiz,
                                                       jstring jprocess_name)
{
    (void) thiz;

    const char *process_name = (*env)->GetStringUTFChars(env, jprocess_name, NULL);
    GError *error = NULL;
    FridaProcess *process = NULL;
    char buf[512];

    g_mutex_lock(&g_lock);

    if (g_device == NULL) {
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jprocess_name, process_name);
        return (*env)->NewStringUTF(env, "err: not connected");
    }

    if (g_session != NULL) {
        frida_unref(g_session);
        g_session = NULL;
    }

    /* Preferred path: look the process up by name ("Gadget"). */
    process = frida_device_get_process_by_name_sync(g_device, process_name,
                                                   NULL, NULL, &error);
    if (error == NULL && process != NULL) {
        g_pid = frida_process_get_pid(process);
        frida_unref(process);
        snprintf(buf, sizeof(buf), "found process %s (pid %u)", process_name, g_pid);
        LOGI("%s", buf);
    } else {
        if (error != NULL) {
            LOGI("process lookup failed (%s), falling back to pid 0", err_text(error));
            g_error_free(error);
            error = NULL;
        }
        g_pid = 0;
    }

    g_session = frida_device_attach_sync(g_device, g_pid, NULL, NULL, &error);
    if (error != NULL) {
        snprintf(buf, sizeof(buf), "err: attach failed: %s", err_text(error));
        g_error_free(error);
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jprocess_name, process_name);
        return (*env)->NewStringUTF(env, buf);
    }

    snprintf(buf, sizeof(buf), "attached (pid %u)", g_pid);
    LOGI("%s", buf);

    g_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jprocess_name, process_name);

    return (*env)->NewStringUTF(env, buf);
}

/* ------------------------------------------------------------------ */
/* JNI: scripts                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeLoadScript(JNIEnv *env, jobject thiz,
                                                           jstring jname, jstring jsource)
{
    (void) thiz;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *source = (*env)->GetStringUTFChars(env, jsource, NULL);
    GError *error = NULL;
    char buf[512];

    g_mutex_lock(&g_lock);

    if (g_session == NULL) {
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        return (*env)->NewStringUTF(env, "err: not attached");
    }

    FridaScriptOptions *options = frida_script_options_new();
    frida_script_options_set_name(options, name);

    /* shim + agent + closing catch: the wrapper is never closed, so any
     * top-level throw in the agent surfaces as a console message. */
    size_t wrapped_len = strlen(CONSOLE_SHIM) + strlen(source) + strlen(EPILOGUE) + 1;
    char *wrapped = (char *) malloc(wrapped_len);
    if (wrapped == NULL) {
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        return (*env)->NewStringUTF(env, "err: out of memory");
    }
    snprintf(wrapped, wrapped_len, "%s%s%s", CONSOLE_SHIM, source, EPILOGUE);

    FridaScript *script = frida_session_create_script_sync(g_session, wrapped, options,
                                                           NULL, &error);
    free(wrapped);
    frida_unref(options);

    if (error != NULL) {
        snprintf(buf, sizeof(buf), "err: create %s failed: %s", name, err_text(error));
        g_error_free(error);
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        return (*env)->NewStringUTF(env, buf);
    }

    /* The slot's own name pointer is the handler's user_data: it outlives this
     * JNI call, unlike the `name` local reference. */
    const char *slot_name = script_slot_add(name, script);
    FridaScript *tracked = script_slot_get(name);

    g_signal_connect(tracked, "message", G_CALLBACK(on_script_message),
                     (gpointer) slot_name);

    frida_script_load_sync(tracked, NULL, &error);
    if (error != NULL) {
        snprintf(buf, sizeof(buf), "err: load %s failed: %s", name, err_text(error));
        g_error_free(error);
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        return (*env)->NewStringUTF(env, buf);
    }

    snprintf(buf, sizeof(buf), "loaded %s", name);
    LOGI("%s", buf);

    g_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jsource, source);

    return (*env)->NewStringUTF(env, buf);
}

/*
 * Posts {"type":"conf","k":<key>,"v":<json_value>} to a loaded script.
 * Matches the agents' `recv("conf", ...)` handler exactly.
 * `json_value` must already be valid JSON (string, number, bool, object...).
 */
JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativePostConf(JNIEnv *env, jobject thiz,
                                                         jstring jname, jstring jkey,
                                                         jstring jjson_value)
{
    (void) thiz;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    const char *json_value = (*env)->GetStringUTFChars(env, jjson_value, NULL);
    char buf[512];

    g_mutex_lock(&g_lock);

    FridaScript *script = script_slot_get(name);
    if (script == NULL) {
        g_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        (*env)->ReleaseStringUTFChars(env, jkey, key);
        (*env)->ReleaseStringUTFChars(env, jjson_value, json_value);
        return (*env)->NewStringUTF(env, "err: script not loaded");
    }

    gchar *payload = g_strdup_printf("{\"type\":\"conf\",\"k\":\"%s\",\"v\":%s}",
                                     key, json_value);
    frida_script_post(script, payload, NULL);

    snprintf(buf, sizeof(buf), "posted %s=%s", key, json_value);
    g_free(payload);

    g_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jkey, key);
    (*env)->ReleaseStringUTFChars(env, jjson_value, json_value);

    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeUnloadScript(JNIEnv *env, jobject thiz,
                                                             jstring jname)
{
    (void) thiz;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    GError *error = NULL;

    g_mutex_lock(&g_lock);

    FridaScript *script = script_slot_get(name);
    if (script != NULL)
        frida_script_unload_sync(script, NULL, &error);

    if (error != NULL) {
        LOGW("unload %s: %s", name, err_text(error));
        g_error_free(error);
    }

    g_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    return (*env)->NewStringUTF(env, "ok");
}

/* ------------------------------------------------------------------ */
/* JNI: session lifecycle                                              */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeResume(JNIEnv *env, jobject thiz)
{
    (void) thiz;

    GError *error = NULL;

    g_mutex_lock(&g_lock);

    if (g_device == NULL) {
        g_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "err: not connected");
    }

    frida_device_resume_sync(g_device, g_pid, NULL, &error);
    if (error != NULL) {
        char buf[512];
        snprintf(buf, sizeof(buf), "err: resume failed: %s", err_text(error));
        g_error_free(error);
        g_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, buf);
    }

    g_mutex_unlock(&g_lock);

    return (*env)->NewStringUTF(env, "resumed");
}

JNIEXPORT jstring JNICALL
Java_com_zucker_openbachelorc_FridaClient_nativeDetach(JNIEnv *env, jobject thiz)
{
    (void) thiz;

    GError *error = NULL;

    g_mutex_lock(&g_lock);

    for (int i = 0; i < g_script_count; i++) {
        if (g_scripts[i].script != NULL)
            frida_unref(g_scripts[i].script);
        free(g_scripts[i].name);
        g_scripts[i].script = NULL;
        g_scripts[i].name = NULL;
    }
    g_script_count = 0;

    if (g_session != NULL) {
        frida_session_detach_sync(g_session, NULL, &error);
        if (error != NULL) {
            LOGW("detach: %s", err_text(error));
            g_error_free(error);
            error = NULL;
        }
        frida_unref(g_session);
        g_session = NULL;
    }

    g_mutex_unlock(&g_lock);

    return (*env)->NewStringUTF(env, "detached");
}
