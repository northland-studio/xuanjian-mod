package top.xuanjian.guild.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 官网 API HTTP 客户端（仅依赖 JDK 内置 HttpClient 与 Gson）
 */
public class ApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("xuanjianmod");
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * 强制 IPv4 优先。
     *
     * <p>踩过的坑：xuanjian.top 在 Cloudflare 上同时有 A 与 AAAA 记录，而 Java 的 HttpClient
     * **不做 Happy Eyeballs 回退** —— 一旦所在网络是"有 IPv6 地址但到 Cloudflare 的 IPv6 路由不通"
     * 的半通状态（国内不少家宽/机房如此），连接会一直等到超时，报 HTTP connect timed out，
     * 表现为"浏览器打得开官网、模组却提示官网服务不可用"。
     *
     * <p>必须在 InetAddress 类初始化之前设置才生效，所以放在静态块；ApiClient 是本模组最早触网的对象。
     */
    static {
        try {
            if (!"true".equalsIgnoreCase(System.getProperty("java.net.preferIPv4Stack"))) {
                System.setProperty("java.net.preferIPv4Stack", "true");
                LOGGER.info("[xuanjianmod] 已强制 IPv4 优先（规避 IPv6 半通导致的连接超时）");
            }
        } catch (Exception e) {
            LOGGER.warn("[xuanjianmod] 设置 IPv4 优先失败，继续用系统默认: {}", e.getMessage());
        }
    }

    private final HttpClient httpClient;
    private String baseUrl;
    private String serverKey;

    public ApiClient(String baseUrl, String serverKey) {
        this.baseUrl = trimSlash(baseUrl);
        this.serverKey = serverKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void update(String baseUrl, String serverKey) {
        this.baseUrl = trimSlash(baseUrl);
        this.serverKey = serverKey;
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * GET 请求，返回 JSON 对象；失败返回 null
     */
    public JsonObject get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), null);
    }

    /**
     * POST 请求（JSON body），返回 JSON 对象；失败返回 null
     */
    public JsonObject post(String path, Map<String, Object> body) {
        String json = body == null ? "{}" : GSON.toJson(body);
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), json);
    }

    private JsonObject send(HttpRequest.Builder builder, String bodyForLog) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        builder.header("User-Agent", "xuanjianmod")
                .timeout(TIMEOUT);
        if (serverKey != null && !serverKey.isBlank()) {
            builder.header("X-Server-Key", serverKey);
        }
        try {
            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (response.body() == null || response.body().isBlank()) return new JsonObject();
                return JsonParser.parseString(response.body()).getAsJsonObject();
            }
            // 非 2xx：尝试透传后端错误信息（如 {error:"申报原因至少10个字符"}），便于玩家看到真实原因
            try {
                String body = response.body();
                if (body != null && !body.isBlank()) {
                    JsonObject err = JsonParser.parseString(body).getAsJsonObject();
                    if (err.has("error")) return err;
                }
            } catch (Exception ignored) {
                // 非 JSON 响应，忽略
            }
            LOGGER.warn("API 请求失败 {} {} -> HTTP {}", methodOf(request), request.uri().getPath(), response.statusCode());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("API 请求异常 {} -> {}", baseUrl, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("API 响应解析失败: {}", e.getMessage());
        }
        return null;
    }

    private static String methodOf(HttpRequest request) {
        return request.method();
    }
}
