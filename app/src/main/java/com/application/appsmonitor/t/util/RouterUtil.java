package com.application.appsmonitor.t.util;

import android.util.Log;

import com.application.appsmonitor.t.AppConst;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * RouterUtil
 * 通过 TP-LINK TL-WDR7650 后台 Web API 实现"设备级"断网：
 *   1) POST &lt;ROUTER_BASE_URL&gt; 登录取 stok
 *      body: {"method":"do","login":{"password":"&lt;已加密&gt;"}}
 *      resp: {"error_code":0,"stok":"..."}
 *   2) POST &lt;ROUTER_BASE_URL&gt;stok=&lt;stok&gt;/ds 拉黑目标 MAC
 *      body: {"hosts_info":{"set_block_flag":{"mac":"...","is_blocked":"1",
 *             "name":"...","down_limit":"0","up_limit":"0",
 *             "forbid_domain":"","limit_time":""}},"method":"do"}
 *      resp: {"error_code":0} 表示成功
 *
 * 重要：
 * - 所有网络调用都是同步阻塞，必须在后台线程执行；调用方负责起线程。
 * - 加密密码来自 {@link AppConst#ROUTER_ENCRYPTED_PASSWORD}，路由恢复出厂/升级固件后可能失效，
 *   届时重新抓浏览器 F12 里的 login.password 替换常量即可。
 * - stok 直接原样拼进 URL，不再做二次 URLEncoder.encode（返回串已经是 URL-encoded 形式）。
 * - Referer 必须等于管理页地址，否则新版 TP-LINK 会拒绝接口调用。
 */
public final class RouterUtil {

    private static final String TAG = "RouterUtil";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private RouterUtil() {}

    /**
     * 一键完成"登录 + 拉黑目标 MAC"。任何一步失败都返回 false，不抛异常。
     * 调用方必须在非 UI 线程执行本方法。
     */
    public static boolean blockTargetDevice() {
        String stok = login();
        if (stok == null || stok.isEmpty()) {
            Log.w(TAG, "login failed, skip block");
            return false;
        }
        return issueBlock(stok);
    }

    /** 登录路由后台，成功返回 stok（已 URL-encoded 的原始串），失败返回 null。 */
    private static String login() {
        try {
            JSONObject login = new JSONObject();
            login.put("password", AppConst.ROUTER_ENCRYPTED_PASSWORD);
            JSONObject body = new JSONObject();
            body.put("method", "do");
            body.put("login", login);

            String resp = post(AppConst.ROUTER_BASE_URL, body.toString());
            if (resp == null) return null;
            JSONObject json = new JSONObject(resp);
            if (json.optInt("error_code", -1) != 0) {
                Log.w(TAG, "login error_code=" + json.optInt("error_code", -1));
                return null;
            }
            return json.optString("stok", null);
        } catch (Exception e) {
            Log.w(TAG, "login exception", e);
            return null;
        }
    }

    /** 携带 stok 调用 hosts_info.set_block_flag 把目标 MAC 拉黑。 */
    private static boolean issueBlock(String stok) {
        try {
            JSONObject flag = new JSONObject();
            flag.put("mac", AppConst.ROUTER_BLOCK_MAC);
            flag.put("is_blocked", "1");
            flag.put("name", AppConst.ROUTER_BLOCK_NAME);
            flag.put("down_limit", "0");
            flag.put("up_limit", "0");
            flag.put("forbid_domain", "");
            flag.put("limit_time", "");

            JSONObject hostsInfo = new JSONObject();
            hostsInfo.put("set_block_flag", flag);

            JSONObject body = new JSONObject();
            body.put("hosts_info", hostsInfo);
            body.put("method", "do");

            // stok 已经 URL-encoded，直接原样拼接；ROUTER_BASE_URL 末尾带 '/'
            String url = AppConst.ROUTER_BASE_URL + "stok=" + stok + "/ds";
            String resp = post(url, body.toString());
            if (resp == null) return false;
            JSONObject json = new JSONObject(resp);
            int code = json.optInt("error_code", -1);
            if (code != 0) {
                Log.w(TAG, "block error_code=" + code + " resp=" + resp);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "block exception", e);
            return false;
        }
    }

    /** 极简 JSON POST。返回响应体字符串，非 2xx 或异常时返回 null。 */
    private static String post(String urlStr, String jsonBody) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            // TP-LINK 后台会校验 Referer 是否是管理页 origin；必须带上
            c.setRequestProperty("Referer", AppConst.ROUTER_BASE_URL);
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");

            byte[] data = jsonBody.getBytes(UTF8);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (is == null) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(is, UTF8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "post failed url=" + urlStr, e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
