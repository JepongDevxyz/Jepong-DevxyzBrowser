#include <jni.h>
#include <openvpn/tun/builder/base.hpp>
#include "client/ovpncli.hpp"

#include <memory>
#include <mutex>
#include <string>
#include <thread>

using openvpn::ClientAPI::AppCustomControlMessageEvent;
using openvpn::ClientAPI::Config;
using openvpn::ClientAPI::Event;
using openvpn::ClientAPI::ExternalPKICertRequest;
using openvpn::ClientAPI::ExternalPKISignRequest;
using openvpn::ClientAPI::LogInfo;
using openvpn::ClientAPI::OpenVPNClient;
using openvpn::ClientAPI::ProvideCreds;

namespace {
JavaVM *g_vm = nullptr;
std::mutex g_client_mutex;
class AndroidOpenVPNClient;
AndroidOpenVPNClient *g_client = nullptr;

class ScopedEnv {
  public:
    explicit ScopedEnv(JavaVM *vm) : vm_(vm) {
        if (vm_->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) attached_ = true;
        }
    }
    ~ScopedEnv() { if (attached_) vm_->DetachCurrentThread(); }
    JNIEnv *get() const { return env_; }
  private:
    JavaVM *vm_;
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

std::string fromJava(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

class AndroidOpenVPNClient final : public OpenVPNClient {
  public:
    explicit AndroidOpenVPNClient(JNIEnv *env, jobject service) {
        env->GetJavaVM(&vm_);
        service_ = env->NewGlobalRef(service);
    }
    ~AndroidOpenVPNClient() override {
        ScopedEnv scoped(vm_);
        if (scoped.get() && service_) scoped.get()->DeleteGlobalRef(service_);
    }

    bool run(const std::string &profile, const std::string &username,
             const std::string &password, const std::string &key_password) {
        Config config;
        config.content = profile;
        config.guiVersion = "DevxyzBrowser 0.2";
        config.privateKeyPassword = key_password;
        const auto evaluated = eval_config(config);
        if (evaluated.error) {
            notify("PROFILE_ERROR", evaluated.message, true, true);
            return false;
        }
        if (!evaluated.autologin) {
            if (username.empty() || password.empty()) {
                notify("AUTH_REQUIRED", "This profile requires a username and password.", true, true);
                return false;
            }
            ProvideCreds creds;
            creds.username = evaluated.userlockedUsername.empty() ? username : evaluated.userlockedUsername;
            creds.password = password;
            const auto supplied = provide_creds(creds);
            if (supplied.error) {
                notify("AUTH_ERROR", supplied.message, true, true);
                return false;
            }
        }
        notify("CONNECTING", "Starting OpenVPN 3 client.", false, false);
        const auto result = connect();
        if (result.error) notify("ERROR", result.message, true, true);
        return !result.error;
    }

    bool pause_on_connection_timeout() override { return false; }
    void event(const Event &event) override {
        notify(event.name, event.info, event.error, event.fatal);
    }
    void acc_event(const AppCustomControlMessageEvent &event) override {
        notify("APP_CONTROL", event.protocol + ": " + event.payload, false, false);
    }
    void log(const LogInfo &info) override { notify("LOG", info.text, false, false); }
    void external_pki_cert_request(ExternalPKICertRequest &request) override {
        request.error = true;
        request.errorText = "External PKI certificates are not supported by this profile adapter.";
    }
    void external_pki_sign_request(ExternalPKISignRequest &request) override {
        request.error = true;
        request.errorText = "External PKI signing is not supported by this profile adapter.";
    }

    bool socket_protect(openvpn_io::detail::socket_type socket, std::string, bool) override {
        return callBoolean("nativeProtectSocket", "(I)Z", static_cast<jint>(socket));
    }

    bool tun_builder_new() override { return callBoolean("nativeTunNew", "()Z"); }
    bool tun_builder_set_layer(int layer) override { return layer == 3; }
    bool tun_builder_set_remote_address(const std::string &, bool) override { return true; }
    bool tun_builder_add_address(const std::string &address, int prefix,
                                 const std::string &, bool, bool) override {
        return callBoolean("nativeTunAddAddress", "(Ljava/lang/String;I)Z", address, prefix);
    }
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        bool ok = true;
        if (ipv4) ok = callBoolean("nativeTunAddRoute", "(Ljava/lang/String;I)Z", "0.0.0.0", 0) && ok;
        if (ipv6) ok = callBoolean("nativeTunAddRoute", "(Ljava/lang/String;I)Z", "::", 0) && ok;
        return ok;
    }
    bool tun_builder_add_route(const std::string &address, int prefix, int, bool) override {
        return callBoolean("nativeTunAddRoute", "(Ljava/lang/String;I)Z", address, prefix);
    }
    bool tun_builder_exclude_route(const std::string &address, int prefix, int, bool) override {
        return callBoolean("nativeTunExcludeRoute", "(Ljava/lang/String;I)Z", address, prefix);
    }
    bool tun_builder_set_dns_options(const openvpn::DnsOptions &dns) override {
        bool ok = true;
        for (const auto &entry : dns.servers) {
            for (const auto &address : entry.second.addresses)
                ok = callBoolean("nativeTunAddDns", "(Ljava/lang/String;)Z", address.address) && ok;
        }
        return ok;
    }
    bool tun_builder_set_mtu(int mtu) override {
        return callBoolean("nativeTunSetMtu", "(I)Z", static_cast<jint>(mtu));
    }
    bool tun_builder_set_session_name(const std::string &name) override {
        return callBoolean("nativeTunSetSession", "(Ljava/lang/String;)Z", name);
    }
    bool tun_builder_add_proxy_bypass(const std::string &) override { return true; }
    bool tun_builder_set_proxy_auto_config_url(const std::string &) override { return false; }
    bool tun_builder_set_proxy_http(const std::string &, int) override { return false; }
    bool tun_builder_set_proxy_https(const std::string &, int) override { return false; }
    bool tun_builder_add_wins_server(const std::string &) override { return true; }
    bool tun_builder_set_allow_family(int family, bool allow) override {
        return callBoolean("nativeTunAllowFamily", "(IZ)Z", static_cast<jint>(family), static_cast<jboolean>(allow));
    }
    int tun_builder_establish() override { return callInt("nativeTunEstablish", "()I"); }
    bool tun_builder_persist() override { return false; }
    void tun_builder_establish_lite() override {}
    void tun_builder_teardown(bool) override { callVoid("nativeTunClosed"); }

  private:
    template <typename... Args>
    bool callBoolean(const char *name, const char *signature, Args... args) {
        ScopedEnv scoped(vm_); JNIEnv *env = scoped.get(); if (!env || !service_) return false;
        jclass cls = env->GetObjectClass(service_); jmethodID method = env->GetMethodID(cls, name, signature);
        if (!method) { env->ExceptionClear(); env->DeleteLocalRef(cls); return false; }
        jboolean result;
        if constexpr (sizeof...(Args) == 0) result = env->CallBooleanMethod(service_, method);
        else result = invokeBoolean(env, method, args...);
        const bool failed = env->ExceptionCheck(); if (failed) env->ExceptionClear();
        env->DeleteLocalRef(cls); return !failed && result == JNI_TRUE;
    }
    jboolean invokeBoolean(JNIEnv *env, jmethodID m, jint n) { return env->CallBooleanMethod(service_, m, n); }
    jboolean invokeBoolean(JNIEnv *env, jmethodID m, jint n, jboolean b) { return env->CallBooleanMethod(service_, m, n, b); }
    jboolean invokeBoolean(JNIEnv *env, jmethodID m, const std::string &s, jint n) {
        jstring js = env->NewStringUTF(s.c_str()); auto result = env->CallBooleanMethod(service_, m, js, n); env->DeleteLocalRef(js); return result;
    }
    jboolean invokeBoolean(JNIEnv *env, jmethodID m, const std::string &s) {
        jstring js = env->NewStringUTF(s.c_str()); auto result = env->CallBooleanMethod(service_, m, js); env->DeleteLocalRef(js); return result;
    }
    int callInt(const char *name, const char *signature) {
        ScopedEnv scoped(vm_); JNIEnv *env = scoped.get(); if (!env || !service_) return -1;
        jclass cls = env->GetObjectClass(service_); jmethodID method = env->GetMethodID(cls, name, signature);
        if (!method) { env->ExceptionClear(); env->DeleteLocalRef(cls); return -1; }
        jint result = env->CallIntMethod(service_, method); const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear(); env->DeleteLocalRef(cls); return failed ? -1 : result;
    }
    void callVoid(const char *name) {
        ScopedEnv scoped(vm_); JNIEnv *env = scoped.get(); if (!env || !service_) return;
        jclass cls = env->GetObjectClass(service_); jmethodID method = env->GetMethodID(cls, name, "()V");
        if (method) env->CallVoidMethod(service_, method);
        if (env->ExceptionCheck()) env->ExceptionClear(); env->DeleteLocalRef(cls);
    }
    void notify(const std::string &name, const std::string &info, bool error, bool fatal) {
        ScopedEnv scoped(vm_); JNIEnv *env = scoped.get(); if (!env || !service_) return;
        jclass cls = env->GetObjectClass(service_);
        jmethodID method = env->GetMethodID(cls, "nativeOnOpenVpnEvent", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
        if (method) {
            jstring jname = env->NewStringUTF(name.c_str()); jstring jinfo = env->NewStringUTF(info.c_str());
            env->CallVoidMethod(service_, method, jname, jinfo, static_cast<jboolean>(error), static_cast<jboolean>(fatal));
            env->DeleteLocalRef(jname); env->DeleteLocalRef(jinfo);
        }
        if (env->ExceptionCheck()) env->ExceptionClear(); env->DeleteLocalRef(cls);
    }

    JavaVM *vm_ = nullptr;
    jobject service_ = nullptr;
};
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jepongdevxyz_browser_vpn_NativeOpenVpn_start(JNIEnv *env, jclass, jobject service,
                                                       jstring profile, jstring username,
                                                       jstring password, jstring key_password) {
    std::lock_guard<std::mutex> lock(g_client_mutex);
    if (g_client) return JNI_FALSE;
    auto *client = new AndroidOpenVPNClient(env, service);
    const std::string config = fromJava(env, profile);
    const std::string user = fromJava(env, username);
    const std::string pass = fromJava(env, password);
    const std::string key = fromJava(env, key_password);
    g_client = client;
    try {
        std::thread([client, config, user, pass, key]() {
            client->run(config, user, pass, key);
            {
                std::lock_guard<std::mutex> done_lock(g_client_mutex);
                if (g_client == client) g_client = nullptr;
            }
            delete client;
        }).detach();
    } catch (...) {
        g_client = nullptr; delete client; return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jepongdevxyz_browser_vpn_NativeOpenVpn_stop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_client_mutex);
    if (g_client) g_client->stop();
}
