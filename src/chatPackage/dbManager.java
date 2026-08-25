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

        // 初始化管理员账号（INSERT IGNORE：已存在则不覆盖，密码不会被重置）
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO Users(username, password, role) VALUES (?, ?, 'admin')")) {
            ps.setString(1, "admin");
            ps.setString(2, "20061021");
            ps.executeUpdate();
        }

        createMessageTables();
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
    public static final int PUBLIC_HISTORY_LIMIT = 100;
    public static final int PRIVATE_HISTORY_LIMIT = 500;

    /** 一条聊天记录（公共消息和私聊消息通用） */
    public static class ChatRecord {
        public final String sender;
        public final long timestamp; // epoch millis
        public final String content;

        public ChatRecord(String sender, long timestamp, String content) {
            this.sender = sender;
            this.timestamp = timestamp;
            this.content = content;
        }
    }

    /**
     * 保存一条公共聊天消息，返回写入的时间戳。
     *
     * 时间戳由 Java 端生成而不是用 MySQL 的 NOW()：同一个值既要入库又要广播给所有客户端，
     * 必须完全一致，否则界面上显示的时间和重登后从历史里读出来的时间会对不上。
     */
    public synchronized long savePublicMessage(String sender, String content) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO PublicMessages(send_time, sender, content) VALUES (?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setString(2, sender);
            ps.setString(3, content);
            ps.executeUpdate();
        }
        return now;
    }

    /** 读取最近 limit 条公共聊天记录，按时间正序返回（便于直接顺序回放） */
    public synchronized List<ChatRecord> getPublicHistory(int limit) throws SQLException {
        List<ChatRecord> records = new ArrayList<>();
        // 先按 id 倒序取最新的 limit 条，再反转成正序
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, content FROM PublicMessages ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new ChatRecord(rs.getString("sender"),
                            rs.getLong("send_time"), rs.getString("content")));
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
     * 保存一条私聊消息，返回写入的时间戳。
     *
     * 同一条消息插入两行：发送者一份（已读）、接收者一份（未读）。用单条 INSERT 带两组
     * VALUES 写入，天然原子，不需要显式事务。两行分别归收发双方所有，因此任何一方
     * 清空自己的记录都不会动到对方那份。
     */
    public synchronized long savePrivateMessage(String from, String to, String content) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO PrivateMessages(`owner`, peer, sender, send_time, content, is_read) "
                        + "VALUES (?, ?, ?, ?, ?, 1), (?, ?, ?, ?, ?, 0)")) {
            // 发送者视角：owner=from, peer=to，自己发的消息直接算已读
            ps.setString(1, from);
            ps.setString(2, to);
            ps.setString(3, from);
            ps.setLong(4, now);
            ps.setString(5, content);
            // 接收者视角：owner=to, peer=from，未读
            ps.setString(6, to);
            ps.setString(7, from);
            ps.setString(8, from);
            ps.setLong(9, now);
            ps.setString(10, content);
            ps.executeUpdate();
        }
        return now;
    }

    /** 读取 owner 与 peer 的最近 limit 条私聊记录，按时间正序返回 */
    public synchronized List<ChatRecord> getPrivateHistory(String owner, String peer, int limit)
            throws SQLException {
        List<ChatRecord> records = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender, send_time, content FROM PrivateMessages "
                        + "WHERE `owner` = ? AND peer = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, owner);
            ps.setString(2, peer);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new ChatRecord(rs.getString("sender"),
                            rs.getLong("send_time"), rs.getString("content")));
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
