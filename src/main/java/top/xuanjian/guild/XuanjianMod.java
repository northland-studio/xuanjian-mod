package top.xuanjian.guild;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuanjian.guild.bind.BindManager;
import top.xuanjian.guild.checkin.CheckinManager;
import top.xuanjian.guild.command.ServerCommandActor;
import top.xuanjian.guild.command.XjCommand;
import top.xuanjian.guild.config.ModConfig;
import top.xuanjian.guild.economy.ClaimManager;
import top.xuanjian.guild.economy.ContributionManager;
import top.xuanjian.guild.network.ApiClient;
import top.xuanjian.guild.online.OnlineManager;
import top.xuanjian.guild.sync.AdminAlertSync;
import top.xuanjian.guild.sync.UpdateSync;
import top.xuanjian.guild.task.TaskManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玄剑公会官网联动模组主入口
 */
public class XuanjianMod implements ModInitializer {
    public static final String MOD_ID = "xuanjianmod";
    public static final String VERSION = "1.0.2";
    private static final Logger LOGGER = LoggerFactory.getLogger("xuanjianmod");

    /** 单例：客户端入口（XuanjianModClient）通过该实例复用全部管理器 */
    private static XuanjianMod INSTANCE;

    public static XuanjianMod getInstance() {
        return INSTANCE;
    }

    private ModConfig config;
    private ApiClient api;
    private BindManager bindManager;
    private CheckinManager checkinManager;
    private TaskManager taskManager;
    private ContributionManager contributionManager;
    private ClaimManager claimManager;
    private OnlineManager onlineManager;
    private UpdateSync updateSync;
    private AdminAlertSync adminAlertSync;

    private long heartbeatCounter = 0;
    private long syncCounter = 0;
    private MinecraftServer server;

    /** 功能7：每玩家最后活跃心跳时间（服务器启动后清零） */
    private final Map<UUID, Long> lastHeartbeat = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("[xuanjianmod] 玄剑公会联动模组加载中 v{}", VERSION);
        INSTANCE = this;

        Path configDir = Path.of("config");
        config = new ModConfig(configDir);
        api = new ApiClient(config.getApiBase(), config.getServerKey());
        bindManager = new BindManager(configDir, api);
        checkinManager = new CheckinManager(api);
        taskManager = new TaskManager(api);
        contributionManager = new ContributionManager(api);
        claimManager = new ClaimManager(api);
        onlineManager = new OnlineManager(api, config.getServerIp());
        updateSync = new UpdateSync(api);
        adminAlertSync = new AdminAlertSync(api);

        registerCommands();
        registerEvents();
        registerTasks();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(new XjCommand<CommandSourceStack>(this, ServerCommandActor::from).build());
        });
    }

    private void registerEvents() {
        // 功能1：登录后自动签到
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) return;
            UUID uuid = player.getUUID();
            bindManager.syncFromServer(uuid); // 邮件确认后本地缓存可能未更新，先同步官网状态
            if (!bindManager.isBound(uuid)) return; // 未绑定不签到

            server.execute(() -> {
                try {
                    Thread.sleep(3000); // 等待玩家加载完成
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                JsonObject resp = checkinManager.checkin(uuid, player.getName().getString());
                if (resp == null) {
                    player.sendSystemMessage(Component.literal("§c[玄剑] 自动签到失败：官网服务不可用"));
                } else if (resp.has("error")) {
                    String err = resp.get("error").getAsString();
                    if (err.contains("今日已签到")) {
                        player.sendSystemMessage(Component.literal("§7[玄剑] 今日已在官网签到"));
                    } else {
                        player.sendSystemMessage(Component.literal("§c[玄剑] 自动签到失败：" + err));
                    }
                } else {
                    int reward = resp.has("rewardPoints") ? resp.get("rewardPoints").getAsInt() : 0;
                    int total = resp.has("totalContribution") ? resp.get("totalContribution").getAsInt() : 0;
                    player.sendSystemMessage(Component.literal("§a[玄剑] 官网签到成功，获得 " + reward + " 贡献点（余额 " + total + "）"));
                }
            });
        });

        // 服务器启动后初始化同步游标
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.server = server;
            lastHeartbeat.clear();
            LOGGER.info("[xuanjianmod] 服务器启动，开始与官网同步");
        });

        // 玩家断开时清理心跳记录
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getPlayer() != null) {
                lastHeartbeat.remove(handler.getPlayer().getUUID());
            }
        });
    }

    private void registerTasks() {
        // 功能7：每 heartbeat.interval 秒上报一次活跃心跳 + 在线玩家
        // 功能5：每 sync.interval 秒同步日报/决策
        // 功能10：每 sync.interval 秒轮询新申报提醒
        ServerTickEvents.END_SERVER_TICK.register(mcServer -> {
            if (mcServer.getTickCount() % 20 != 0) return; // 每秒一次
            heartbeatCounter++;
            syncCounter++;

            int heartbeatInterval = Math.max(config.getHeartbeatInterval(), 30);
            int syncInterval = Math.max(config.getSyncInterval(), 30);

            if (heartbeatCounter >= heartbeatInterval) {
                heartbeatCounter = 0;
                doHeartbeat(mcServer);
            }
            if (syncCounter >= syncInterval) {
                syncCounter = 0;
                doSync(mcServer);
            }
        });
    }

    /** 功能7：上报活跃心跳（在线列表已改由客户端上下线上报，服务端不再上报 mod_online） */
    private void doHeartbeat(MinecraftServer mcServer) {
        if (api.getBaseUrl() == null || api.getBaseUrl().isBlank()) return;

        List<OnlineManager.OnlinePlayer> onlinePlayers = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
            UUID uuid = p.getUUID();
            String name = p.getName().getString();
            lastHeartbeat.put(uuid, now);
            onlinePlayers.add(new OnlineManager.OnlinePlayer(uuid, name));
        }

        // 活跃心跳上报
        Map<String, Object> body = new HashMap<>();
        body.put("players", lastHeartbeat.keySet().stream().map(UUID::toString).toList());
        body.put("onlineCount", onlinePlayers.size());
        api.post("/api/mod/heartbeat", body);
        LOGGER.info("[心跳] 上报 {} 名在线玩家活跃状态", onlinePlayers.size());
    }

    /** 功能5+10：同步日报/决策 + 管理员审核提醒（定向发送） */
    private void doSync(MinecraftServer mcServer) {
        // 功能5：日报/决策更新（仅通知已绑定官网账号的在线玩家）
        try {
            List<JsonObject> updates = updateSync.pollNew();
            if (!updates.isEmpty()) {
                List<ServerPlayer> targets = new ArrayList<>();
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (bindManager.isBound(p.getUUID())) targets.add(p);
                }
                for (JsonObject u : updates) {
                    String type = u.has("type") ? u.get("type").getAsString() : "";
                    String title = u.has("title") ? u.get("title").getAsString() : "";
                    String typeText = "daily".equals(type) ? "日报" : "决策公示";
                    sendTo(targets, "§e[玄剑] 官网发布新" + typeText + "：§f" + title + " §7→ xuanjian.top");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("日报/决策同步异常: {}", e.getMessage());
        }

        // 功能10：新申报审核提醒（仅通知在线的官网管理员）
        try {
            List<JsonObject> claims = adminAlertSync.pollNew();
            if (!claims.isEmpty()) {
                Set<String> adminSet = new HashSet<>(adminAlertSync.getAdminUuids());
                List<ServerPlayer> admins = new ArrayList<>();
                for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                    if (adminSet.contains(p.getUUID().toString())) admins.add(p);
                }
                for (JsonObject c : claims) {
                    String nickname = c.has("nickname") ? c.get("nickname").getAsString() : "玩家";
                    int amount = c.has("amount") ? c.get("amount").getAsInt() : 0;
                    sendTo(admins, "§e[玄剑] 新的贡献点申报待审核：§f" + nickname + " §7申报 §a" + amount + " §7贡献点，请前往官网管理后台处理");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("申报提醒同步异常: {}", e.getMessage());
        }
    }

    private void sendTo(List<ServerPlayer> players, String msg) {
        for (ServerPlayer p : players) {
            p.sendSystemMessage(Component.literal(msg));
        }
        LOGGER.info("[定向发送] {} 人 <- {}", players.size(), msg);
    }

    /* ============ 访问器 ============ */

    public ModConfig getConfig() { return config; }
    public ApiClient getApi() { return api; }

    /** 设置页保存后应用新配置（重新绑定 API 地址与服务器信息） */
    public void applyConfig() {
        api.update(config.getApiBase(), config.getServerKey());
        onlineManager.updateServerIp(config.getServerIp());
        LOGGER.info("[xuanjianmod] 配置已应用：api={}，本服地址={}", config.getApiBase(), config.getServerIp());
    }

    public BindManager getBindManager() { return bindManager; }
    public CheckinManager getCheckinManager() { return checkinManager; }
    public TaskManager getTaskManager() { return taskManager; }
    public ContributionManager getContributionManager() { return contributionManager; }
    public ClaimManager getClaimManager() { return claimManager; }
    public OnlineManager getOnlineManager() { return onlineManager; }
    public UpdateSync getUpdateSync() { return updateSync; }
    public AdminAlertSync getAdminAlertSync() { return adminAlertSync; }
}
