package chatPackage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天室用户数据库管理（MySQL）
 *
 * 负责：
 *  - 连接 MySQL，自动创建 lanchat 库、Users 表、PublicMessages 表、PrivateMessages 表
 *  - 用户注册（INSERT）、登录验证（SELECT 比对）、读取全部用户名（用于离线用户列表）
 *  - 公共聊天记录和私聊记录的持久化、历史查询、未读统计、清理
 *
 * 线程安全：多个客户端线程会并发访问数据库，所有方法加 synchronized，
 * 局域网规模（百人级）下单连接 + 串行访问足够，避免连接池复杂度。
 *
 * 但正因为是「单连接 + 全局串行」，历史查询必须带 LIMIT：一次无界查询会让所有
 * 客户端的数据库操作排队，而登录侧有 5 秒硬超时（chatEntryUI.RESPONSE_TIMEOUT_SECONDS），
 * 查询一慢就会直接导致其他人登录失败。
 *
 * 同样出于这个原因，synchronized 方法内只负责物化数据（把 ResultSet 读进 List 就返回），
 * socket 写入一律留到锁外由调用方处理，不要把网络 IO 卷进数据库锁里。
 */
public class dbManager {

    private static final String DB_NAME = "lanchat";

    private final Connection conn;

    public dbManager(String host, int port, String user, String password) throws SQLException {
        // 先连到 MySQL 服务器（不指定库），自动创建 lanchat 数据库
        String serverUrl = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        conn = DriverManager.getConnection(serverUrl, user, password);

        // 创建数据库（utf8mb4 支持中文和 emoji）
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB_NAME
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        conn.setCatalog(DB_NAME);

        // 创建 Users 表：用户名（主键）+ 密码 + 角色（admin 管理员 / user 普通用户）
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS Users ("
                    + "username VARCHAR(50) NOT NULL PRIMARY KEY, "
                    + "password VARCHAR(100) NOT NULL, "
                    + "role VARCHAR(20) NOT NULL DEFAULT 'user') "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        // 兼容旧表：早期版本的 Users 表没有 role 列，检测后补列（已有用户自动成为普通用户）
        try (var rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "Users", "role")) {
            if (!rs.next()) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE Users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'user'");
                }
            }
        }

        // 兼容旧表：无 avatar 列则补列（自定义头像功能），NULL = 未设置头像
        try (var rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "Users", "avatar")) {
            if (!rs.next()) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE Users ADD COLUMN avatar MEDIUMBLOB NULL");
                }
            }
        }

        // 初始化管理员账号（INSERT IGNORE：已存在则不覆盖，密码不会被重置）
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO Users(username, password, role) VALUES (?, ?, 'admin')")) {
            ps.setString(1, "admin");
            ps.setString(2, "20061021");
            ps.executeUpdate();
        }

        createMessageTables();
        migrateMessageTables();
    }

    /**
     * 消息表兼容迁移：给 PublicMessages / PrivateMessages 加 client_id 列和索引。
     *
     * client_id 是每条消息的全局唯一标识（服务端生成），供消息撤回按 ID 定位。
     * 老数据该列为 NULL，表示「不支持撤回」。沿用上面 role 列的检测-补列模式。
     */
    private void migrateMessageTables() throws SQLException {
        try (var rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "PublicMessages", "client_id")) {
            if (!rs.next()) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE PublicMessages ADD COLUMN client_id VARCHAR(36) NULL");
                    st.executeUpdate("ALTER TABLE PublicMessages ADD INDEX idx_cid (client_id)");
                }
            }
        }
        try (var rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "PrivateMessages", "client_id")) {
            if (!rs.next()) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE PrivateMessages ADD COLUMN client_id VARCHAR(36) NULL");
                    st.executeUpdate("ALTER TABLE PrivateMessages ADD INDEX idx_cid (client_id)");
                }
            }
        }

        // 图片消息：content 列从 TEXT（64KB）扩到 MEDIUMTEXT（16MB）。
        // 只检测是否为 TEXT（旧表），已是 MEDIUMTEXT 则跳过。
        migrateContentToMediumText("PublicMessages");
        migrateContentToMediumText("PrivateMessages");
    }

    /** 消息表 content 列检测-升级：TEXT 旧列 MODIFY 成 MEDIUMTEXT（容纳 [IMG]Base64 图片消息） */
    private void migrateContentToMediumText(String table) throws SQLException {
        try (var rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, "content")) {
            if (rs.next() && "TEXT".equalsIgnoreCase(rs.getString("TYPE_NAME"))) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE " + table + " MODIFY content MEDIUMTEXT NOT NULL");
                }
            }
        }
    }

    /**
     * 创建聊天记录表。
     *
     * send_time 用 BIGINT 存 epoch millis 而不是 DATETIME：DATETIME 的读写要经过
     * rs.getTimestamp()，其时区解释依赖 JVM 默认时区与连接串 serverTimezone 的配合，
     * 两者不一致（换机器、JVM 默认 UTC）会让全部历史消息时间整体偏移。存 BIGINT 则
     * 协议、内存、数据库里始终是同一个数，零转换。消息排序由自增 id 承担，
     * send_time 不参与排序。
     */
    private void createMessageTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            // 公共聊天室消息
            st.executeUpdate("CREATE TABLE IF NOT EXISTS PublicMessages ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "send_time BIGINT NOT NULL, "
                    + "sender VARCHAR(50) NOT NULL, "
                    + "content TEXT NOT NULL) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            // 私聊消息：每条消息存两行，owner 标识这行归谁所有（视角）。
            // 这样 A 清空自己的记录时只删 owner='A' 的行，B 的 owner='B' 行完好无损。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS PrivateMessages ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "`owner` VARCHAR(50) NOT NULL, "   // 这行归谁所有
                    + "peer VARCHAR(50) NOT NULL, "      // 会话对方
                    + "sender VARCHAR(50) NOT NULL, "    // 实际发送者
                    + "send_time BIGINT NOT NULL, "
                    + "content TEXT NOT NULL, "
                    + "is_read TINYINT(1) NOT NULL DEFAULT 0, "
                    + "INDEX idx_conv (`owner`, peer, id), "
                    + "INDEX idx_unread (`owner`, is_read)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    /** 查询用户头像字节，未设置返回 null（与「设置过、图片恰为空」不冲突，存入时已拒绝空数据） */
    public synchronized byte[] getAvatar(String username) throws SQLException {
        String sql = "SELECT avatar FROM Users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes("avatar");
                }
            }
        }
        return null;
    }

    /** 保存/清除用户头像（data 为 null 或空 = 清除）。返回用户是否存在 */
    public synchronized boolean setAvatar(String username, byte[] data) throws SQLException {
        String sql = "UPDATE Users SET avatar = ? WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, data == null || data.length == 0 ? null : data);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        }
    }

    /** 查询用户角色，返回 "admin" / "user"；用户不存在返回 null */
    public synchronized String getRole(String username) throws SQLException {
        String sql = "SELECT role FROM Users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("role");
            }
        }
    }

    /**
     * 封禁用户：在同一事务内删除该用户的全部私聊记录和账号本身。
     * 返回 true 表示确实删掉了账号（false 表示用户本来就不存在）。
     *
     * 必须是事务，否则并发下会出问题：如果先删账号再删消息，别人发给该用户的私聊
     * 恰好插在两步之间，就会留下 peer 指向已注销账号的孤儿行，而清理已经跑过，
     * 这些行永远清不掉。更严重的是服务端没有封号黑名单，任何人都能重新注册同名账号，
     * 新账号登录后会查出前任的私聊记录，造成跨账号隐私泄漏。
     *
     * 私聊记录按 owner 或 peer 匹配全删（连对方持有的那份副本一起清），
     * 与「封禁即删除该用户所有数据」的语义一致，也避免对方侧边栏出现点不开的幽灵会话。
     */
    public synchronized boolean banUser(String username) throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM PrivateMessages WHERE `owner` = ? OR peer = ?")) {
                ps.setString(1, username);
                ps.setString(2, username);
                ps.executeUpdate();
            }
            boolean deleted;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM Users WHERE username = ?")) {
                ps.setString(1, username);
                deleted = ps.executeUpdate() > 0;
            }
            conn.commit();
            return deleted;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** 注册新用户。返回 true 成功；false 表示用户名已存在 */
    public synchronized boolean register(String username, String password) throws SQLException {
        String sql = "INSERT INTO Users(username, password) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            return false; // username 主键冲突：已注册
        }
    }

    /** 验证用户名密码。返回 true 表示用户名存在且密码正确 */
    public synchronized boolean verify(String username, String password) throws SQLException {
        String sql = "SELECT password FROM Users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false; // 用户名不存在
                }
                return rs.getString("password").equals(password);
            }
        }
    }

    /** 判断用户名是否已注册 */
    public synchronized boolean userExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM Users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 读取所有已注册用户名（含在线和离线） */
    public synchronized List<String> getAllUsernames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM Users")) {
            while (rs.next()) {
                names.add(rs.getString("username"));
            }
        }
        return names;
    }

    // ===== 聊天记录 =====

    /** 单条历史查询的默认上限，防止无界查询长时间持锁拖垮其他客户端 */
    public static final int PUBLIC_HISTORY_LIMIT = 500;
    public static final int PRIVATE_HISTORY_LIMIT = 500;

    /** 搜索返回结果上限 */
    private static final int SEARCH_RESULT_LIMIT = 100;

    /** 搜索只在最近这么多条消息内进行，避免无匹配词时全表扫（单连接全局锁，见类注释） */
    private static final long SEARCH_WINDOW = 5000;

    /** 一条聊天记录（公共消息和私聊消息通用）。msgId 为 null 表示老数据不支持撤回 */
    public static class ChatRecord {
        public final String sender;
        public final long timestamp; // epoch millis
        public final String content;
        public final String msgId;

        public ChatRecord(String sender, long timestamp, String content, String msgId) {
            this.sender = sender;
            this.timestamp = timestamp;
            this.content = content;
            this.msgId = msgId;
        }
    }

    /** 刚写入的消息信息（时间戳 + 服务端生成的 msgId），供服务端广播用 */
    public static class SavedMessage {
        public final long timestamp;
        public final String msgId;

        public SavedMessage(long timestamp, String msgId) {
            this.timestamp = timestamp;
            this.msgId = msgId;
        }
    }

    /** 撤回前查询到的消息信息，供服务端做权限和时限校验。查不到/已删返回 null */
    public static class RecallInfo {
        public final String sender;
        public final long sendTime;
        public final String peer;       // 私聊才有，公共为 null
        public final boolean wasUnread; // 私聊才有意义：接收方那份是否还是未读

        public RecallInfo(String sender, long sendTime, String peer, boolean wasUnread) {
            this.sender = sender;
            this.sendTime = sendTime;
            this.peer = peer;
            this.wasUnread = wasUnread;
        }
    }

    /**
     * 保存一条公共聊天消息，返回时间戳和服务端生成的 msgId。
     *
     * 时间戳由 Java 端生成而不是用 MySQL 的 NOW()：同一个值既要入库又要广播给所有客户端，
     * 必须完全一致，否则界面上显示的时间和重登后从历史里读出来的时间会对不上。
     *
     * msgId 由服务端生成（UUID）而不是客户端提供：client_id 是协议字段，客户端可以读到
     * 历史消息里的所有 ID。若允许客户端指定 ID，恶意客户端就能给自己新发的消息复用他人
     * 的 ID，然后「撤回自己的消息」时把同 ID 的他人消息一并删掉。服务端生成后 ID 不可
     * 预知、不可指定，这条攻击路径关闭。
     */
    public synchronized SavedMessage savePublicMessage(String sender, String content) throws SQLException {
        long now = System.currentTimeMillis();
        String msgId = UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO PublicMessages(send_time, sender, content, client_id) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setString(2, sender);
            ps.setString(3, content);
            ps.setString(4, msgId);
            ps.executeUpdate();
        }
        return new SavedMessage(now, msgId);
    }

    /** 读取最近 limit 条公共聊天记录，按时间正序返回（便于直接顺序回放） */
    public synchronized List<ChatRecord> getPublicHistory(int limit) throws SQLException {
        List<ChatRecord> records = new ArrayList<>();
        // 先按 id 倒序取最新的 limit 条，再反转成正序
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, content, client_id FROM PublicMessages ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new ChatRecord(rs.getString("sender"),
                            rs.getLong("send_time"), rs.getString("content"), rs.getString("client_id")));
                }
            }
        }
        Collections.reverse(records);
        return records;
    }

    /** 清空公共聊天记录（管理员操作），返回删除的条数 */
    public synchronized int clearPublicMessages() throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate("DELETE FROM PublicMessages");
        }
    }

    /**
     * 保存一条私聊消息，返回时间戳和服务端生成的 msgId。
     *
     * 同一条消息插入两行：发送者一份（已读）、接收者一份（未读）。用单条 INSERT 带两组
     * VALUES 写入，天然原子，不需要显式事务。两行分别归收发双方所有，因此任何一方
     * 清空自己的记录都不会动到对方那份。两行共享同一个 client_id——撤回时一条
     * DELETE WHERE client_id=? 同时删掉双方的行（撤回即删除，与微信一致）。
     */
    public synchronized SavedMessage savePrivateMessage(String from, String to, String content)
            throws SQLException {
        long now = System.currentTimeMillis();
        String msgId = UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO PrivateMessages(`owner`, peer, sender, send_time, content, is_read, client_id) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?), (?, ?, ?, ?, ?, 0, ?)")) {
            // 发送者视角：owner=from, peer=to，自己发的消息直接算已读
            ps.setString(1, from);
            ps.setString(2, to);
            ps.setString(3, from);
            ps.setLong(4, now);
            ps.setString(5, content);
            ps.setString(6, msgId);
            // 接收者视角：owner=to, peer=from，未读
            ps.setString(7, to);
            ps.setString(8, from);
            ps.setString(9, from);
            ps.setLong(10, now);
            ps.setString(11, content);
            ps.setString(12, msgId);
            ps.executeUpdate();
        }
        return new SavedMessage(now, msgId);
    }

    /** 读取 owner 与 peer 的最近 limit 条私聊记录，按时间正序返回 */
    public synchronized List<ChatRecord> getPrivateHistory(String owner, String peer, int limit)
            throws SQLException {
        List<ChatRecord> records = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, content, client_id FROM PrivateMessages "
                        + "WHERE `owner` = ? AND peer = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, owner);
            ps.setString(2, peer);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new ChatRecord(rs.getString("sender"),
                            rs.getLong("send_time"), rs.getString("content"), rs.getString("client_id")));
                }
            }
        }
        Collections.reverse(records);
        return records;
    }

    /**
     * 清空 owner 与 peer 的私聊记录，返回删除条数。
     * 只删 owner 自己那份，对方持有的副本不受影响——这正是双份行模型的意义。
     */
    public synchronized int clearPrivateHistory(String owner, String peer) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM PrivateMessages WHERE `owner` = ? AND peer = ?")) {
            ps.setString(1, owner);
            ps.setString(2, peer);
            return ps.executeUpdate();
        }
    }

    /** 把 owner 与 peer 的未读私聊标记为已读，返回影响条数 */
    public synchronized int markPrivateRead(String owner, String peer) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE PrivateMessages SET is_read = 1 "
                        + "WHERE `owner` = ? AND peer = ? AND is_read = 0")) {
            ps.setString(1, owner);
            ps.setString(2, peer);
            return ps.executeUpdate();
        }
    }

    /**
     * 撤回公共消息：校验并删除。
     *
     * 返回值：null=消息不存在或已被撤回（先查再删的并发下后到者得到 null，不会重复广播）；
     * 负值=校验失败（PERMISSION 无权 / TIMEOUT 超时，行未动）；非负=撤回成功（1 行已删）。
     *
     * 校验、删除必须在同一个 synchronized 方法内完成，且顺序必须是先校验后删除——
     * 若先删再校验，越权撤回会先把行删掉再报「无权」，消息就真的丢了。
     * 管理员豁免时限校验（但不能撤空）。
     */
    public static final int RECALL_OK = 1;
    public static final int RECALL_PERMISSION_DENIED = -1;
    public static final int RECALL_TIMEOUT = -2;

    public synchronized int recallPublic(String clientId, String operator, boolean operatorIsAdmin,
                                         long windowMs) throws SQLException {
        RecallInfo info = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time FROM PublicMessages WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    info = new RecallInfo(rs.getString("sender"), rs.getLong("send_time"), null, false);
                }
            }
        }
        if (info == null) {
            return 0; // 不存在或已被撤
        }
        if (!info.sender.equals(operator) && !operatorIsAdmin) {
            return RECALL_PERMISSION_DENIED;
        }
        if (!operatorIsAdmin && System.currentTimeMillis() - info.sendTime > windowMs) {
            return RECALL_TIMEOUT;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM PublicMessages WHERE client_id = ?")) {
            ps.setString(1, clientId);
            return ps.executeUpdate() > 0 ? RECALL_OK : 0;
        }
    }

    /**
     * 撤回私聊消息：校验并删除双份行。
     * 返回值语义同 recallPublic。只有发送者本人能撤自己的私聊（管理员在私聊里没有特权）。
     */
    public synchronized int recallPrivate(String clientId, String operator, long windowMs)
            throws SQLException {
        String sender = null;
        long sendTime = 0;
        String peer = null;
        boolean wasUnread = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, peer, is_read FROM PrivateMessages WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (sender == null) { // 发送者自己的那份：is_read=1，peer=会话对方
                        sender = rs.getString("sender");
                        sendTime = rs.getLong("send_time");
                        peer = rs.getString("peer");
                    } else if (rs.getInt("is_read") == 0) { // 接收者的那份
                        wasUnread = true;
                    }
                }
            }
        }
        if (sender == null) {
            return 0;
        }
        if (!sender.equals(operator)) {
            return RECALL_PERMISSION_DENIED;
        }
        if (System.currentTimeMillis() - sendTime > windowMs) {
            return RECALL_TIMEOUT;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM PrivateMessages WHERE client_id = ?")) {
            ps.setString(1, clientId);
            return ps.executeUpdate() > 0 ? RECALL_OK : 0;
        }
    }

    /**
     * 撤回前查询私聊消息的元信息（必须在删除前调用，删除后行就没了）：
     * [0]=senderPeer 发送者视角的会话对方（即接收者）；[1]=wasUnread 接收者那份是否未读。
     * 消息不存在时返回 null。
     *
     * is_read 对发送者永远是 1（发送者视角无「未读」概念），对接收者是 0/1，
     * 所以 is_read=1 的行一定是发送者那份。双份行由单条 INSERT 原子写入，
     * 消息存在则发送者行必然存在，不用兜底。
     * 接收者视角的 peer 永远是发送者本人，不需要查库。
     */
    public synchronized String[] privateRecallMeta(String clientId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT peer, is_read FROM PrivateMessages WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                String peer = null;
                boolean wasUnread = false;
                while (rs.next()) {
                    if (rs.getInt("is_read") == 1) {
                        peer = rs.getString("peer");
                    } else {
                        wasUnread = true;
                    }
                }
                return peer == null ? null : new String[]{peer, String.valueOf(wasUnread)};
            }
        }
    }

    /**
     * 关键词搜索公共聊天记录，按时间倒序返回最新结果。
     *
     * 搜索窗口限定在最近 SEARCH_WINDOW 条内（id > MAX(id)-窗口）：LIKE '%kw%' 无匹配词时
     * 会全表扫，本类持有全局 synchronized 单连接，一次全表扫会让登录、发消息全部排队，
     * 而登录侧有 5 秒硬超时。MAX(id) 走主键索引 O(1)，代价是更早的消息搜不到——界面
     * 本来就只回放最近 500 条，可接受。
     */
    public synchronized List<ChatRecord> searchPublic(String keyword, int limit) throws SQLException {
        List<ChatRecord> results = new ArrayList<>();
        String kw = escapeLike(keyword);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, content, client_id FROM PublicMessages "
                        + "WHERE id > (SELECT MAX(id) - ? FROM PublicMessages) "
                        + "AND content LIKE ? ESCAPE '\\\\' ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, SEARCH_WINDOW);
            ps.setString(2, "%" + kw + "%");
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ChatRecord(rs.getString("sender"),
                            rs.getLong("send_time"), rs.getString("content"), rs.getString("client_id")));
                }
            }
        }
        return results;
    }

    /** 关键词搜索 owner 与 peer 的私聊记录，按时间倒序返回最新结果 */
    public synchronized List<ChatRecord> searchPrivate(String owner, String peer, String keyword, int limit)
            throws SQLException {
        List<ChatRecord> results = new ArrayList<>();
        String kw = escapeLike(keyword);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, content, client_id FROM PrivateMessages "
                        + "WHERE `owner` = ? AND peer = ? "
                        + "AND id > (SELECT MAX(id) - ? FROM PrivateMessages WHERE `owner` = ? AND peer = ?) "
                        + "AND content LIKE ? ESCAPE '\\\\' ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, owner);
            ps.setString(2, peer);
            ps.setLong(3, SEARCH_WINDOW);
            ps.setString(4, owner);
            ps.setString(5, peer);
            ps.setString(6, "%" + kw + "%");
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ChatRecord(rs.getString("sender"),
                            rs.getLong("send_time"), rs.getString("content"), rs.getString("client_id")));
                }
            }
        }
        return results;
    }

    /** 转义 LIKE 通配符，让用户输入的 % _ \ 按字面匹配 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 【仅供集成测试】把指定消息的 send_time 改掉，用来模拟「超过2分钟无法撤回」。
     * 表名只允许白名单里的两张消息表，防止误用。
     */
    public synchronized void setSendTimeForTest(String table, String clientId, long newTime)
            throws SQLException {
        if (!"PublicMessages".equals(table) && !"PrivateMessages".equals(table)) {
            throw new SQLException("不允许修改表 " + table);
        }
        String sql = table.equals("PublicMessages")
                ? "UPDATE PublicMessages SET send_time = ? WHERE client_id = ?"
                : "UPDATE PrivateMessages SET send_time = ? WHERE client_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newTime);
            ps.setString(2, clientId);
            ps.executeUpdate();
        }
    }

    /** 统计 owner 与某个 peer 之间的未读条数 */
    public synchronized int countUnread(String owner, String peer) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM PrivateMessages WHERE `owner` = ? AND peer = ? AND is_read = 0")) {
            ps.setString(1, owner);
            ps.setString(2, peer);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 读取 owner 的全部未读汇总：对方用户名 -> 未读条数（只含有未读的会话） */
    public synchronized Map<String, Integer> getUnreadSummary(String owner) throws SQLException {
        Map<String, Integer> summary = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT peer, COUNT(*) AS cnt FROM PrivateMessages "
                        + "WHERE `owner` = ? AND is_read = 0 GROUP BY peer")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    summary.put(rs.getString("peer"), rs.getInt("cnt"));
                }
            }
        }
        return summary;
    }

    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
