package chatPackage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天室用户数据库管理（MySQL）
 *
 * 负责：
 *  - 连接 MySQL，自动创建 lanchat 库和 Users 表（username 主键、password 字段）
 *  - 用户注册（INSERT）、登录验证（SELECT 比对）、读取全部用户名（用于离线用户列表）
 *
 * 线程安全：多个客户端线程会并发访问数据库，所有方法加 synchronized，
 * 局域网规模（百人级）下单连接 + 串行访问足够，避免连接池复杂度。
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

        // 创建 Users 表：用户名（主键）+ 密码
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS Users ("
                    + "username VARCHAR(50) NOT NULL PRIMARY KEY, "
                    + "password VARCHAR(100) NOT NULL) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
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

    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
