package com.application.appsmonitor.t;

/**
 * AppConst
 * Created by zb on 18/12/2017.
 */

public final class AppConst {
    public static final boolean CRASH_TO_FILE = false;
    public static final long USAGE_TIME_MIX = 5000;
    public static final String GP_DETAIL_PREFIX = "https://play.google.com/store/apps/details?id=";
    public static final String AF_KEY = "cb34ade7-ee68-48f4-91c0-0eb5fc6c2704";
    public static final String BUG_KEY = "4a59b2abb6";
    public static final String LOG_DIR = "com.application.appsmonitor.t";
    public static final long REMOTE_CONFIG_CACHE_TIME = 6 * 60 * 60;

    // ===== 实时时长监控（固定档位提醒）配置 =====
    // 监控以下包名（可用 adb shell pm list packages 或“应用详情”页顶部 pkg_name 查看）。
    public static final String[] MONITOR_PACKAGES = {
            "com.netease.party",    // 网易·蛋仔派对
    };
    // 每日使用上限（分钟），用于计算“还剩多少分钟”，同时也是触发拉黑的阈值
    public static final int MONITOR_DAILY_LIMIT_MIN = 120;
    // 固定提醒档位（已用分钟数达到即提醒一次，同日同档不重复）
    // 【测试配置】每 5 分钟提醒一次；测试交付前改回真实档位，
    // 并先清除 App 数据（去重键在 SharedPreferences里会跨重启保留）。
    public static final int[] MONITOR_TIERS_MIN = {
            5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60,
            65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 120
    };
    // 真实交付时应改回下列稀疏档位：
    // public static final int[] MONITOR_TIERS_MIN = {1, 20, 55, 115};
    // 轮询间隔（毫秒）
    public static final long MONITOR_CHECK_INTERVAL_MS = 60 * 1000L;
    // 每分钟精确唤醒闹钟的 action（由系统 AlarmManager 触发，应用退后台/被杀也能弹提醒）
    public static final String MONITOR_ALARM_ACTION = "MONITOR_ALARM_RECEIVER";

    // ===== 超上限自动断网（通过 TP-LINK TL-WDR7650 路由后台 API 拉黑设备）=====
    // 命中条件：当日已用分钟数 > MONITOR_DAILY_LIMIT_MIN（即“剩余 < 0”）
    // 开启后，App 会自动 POST 路由器登录接口取 stok，再调 hosts_info.set_block_flag 拉黑目标 MAC。
    // 目标设备会瞬间失去外网，WiFi 图标仍在但网关不通；恢复需手动到路由器页取消拉黑。
    public static final boolean MONITOR_ROUTER_BLOCK_ON_OVERDUE = true;

    // 路由管理地址（本 APK 必须与该路由在同一局域网段才能访问）
    public static final String ROUTER_BASE_URL = "http://192.168.0.1/";

    // 登录接口用的“已加密密码”——注意不是明文！
    // 抓包来源：浏览器 F12 → 登录请求 → JSON body.login.password
    // TP-LINK 前端会用登录页内嵌的公钥 XOR+base64 把明文加密成这个串；
    // 同一固件安装周期内一般稳定，若路由器恢复出厂/升级固件后失效，重新抓一次替换即可。
    public static final String ROUTER_ENCRYPTED_PASSWORD = "HKc80yMw9TefbwK";

    // 要拉黑的目标设备（本 pad）——MAC 是主键，name 只是路由页面显示用
    // MAC 格式与 TP-LINK 后台一致：小写、连字符分隔
    public static final String ROUTER_BLOCK_MAC = "1e-2e-0b-24-c6-f0";
    public static final String ROUTER_BLOCK_NAME = "Redmi-Pad-Pro";
}
