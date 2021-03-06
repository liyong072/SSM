/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.metastore.utils;

import com.google.common.hash.Hashing;
import com.mysql.jdbc.NonRegisteringDriver;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartdata.conf.SmartConf;
import org.smartdata.conf.SmartConfKeys;
import org.smartdata.metastore.DruidPool;
import org.smartdata.metastore.MetaStore;
import org.smartdata.metastore.MetaStoreException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.jdbc.support.JdbcUtils.closeConnection;

/**
 * Utilities for table operations.
 */
public class MetaStoreUtils {
  public static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";
  public static final String MYSQL_URL_PREFIX = "jdbc:mysql:";
  public static final String[] DB_NAME_NOT_ALLOWED =
      new String[] {
        "mysql",
        "sys",
        "information_schema",
        "INFORMATION_SCHEMA",
        "performance_schema",
        "PERFORMANCE_SCHEMA"
      };
  static final Logger LOG = LoggerFactory.getLogger(MetaStoreUtils.class);
  private static int characterTakeUpBytes = 1;

  public static final String TABLESET[] = new String[]{
            "access_count_table",
            "blank_access_count_info",
            "cached_file",
            "ec_policy",
            "file",
            "storage",
            "storage_hist",
            "storage_policy",
            "xattr",
            "datanode_info",
            "datanode_storage_info",
            "rule",
            "cmdlet",
            "action",
            "file_diff",
            "global_config",
            "cluster_config",
            "sys_info",
            "cluster_info",
            "backup_file",
            "file_state",
            "compression_file",
            "small_file",
            "user_info"
  };

  public static Connection createConnection(String url,
      String userName, String password)
      throws ClassNotFoundException, SQLException {
    if (url.startsWith(SQLITE_URL_PREFIX)) {
      Class.forName("org.sqlite.JDBC");
    } else if (url.startsWith(MYSQL_URL_PREFIX)) {
      Class.forName("com.mysql.jdbc.Driver");
    }
    Connection conn = DriverManager.getConnection(url, userName, password);
    return conn;
  }

  public static Connection createConnection(String driver, String url,
      String userName,
      String password) throws ClassNotFoundException, SQLException {
    Class.forName(driver);
    Connection conn = DriverManager.getConnection(url, userName, password);
    return conn;
  }

  public static Connection createSqliteConnection(String dbFilePath)
      throws MetaStoreException {
    try {
      return createConnection("org.sqlite.JDBC", SQLITE_URL_PREFIX + dbFilePath,
          null, null);
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
  }

  public static int getTableSetNum(Connection conn, String tableSet[]) throws MetaStoreException {
    String tables = "('" + StringUtils.join(tableSet, "','") + "')";
    try {
      String url = conn.getMetaData().getURL();
      String query;
      if (url.startsWith(MetaStoreUtils.MYSQL_URL_PREFIX)) {
        String dbName = getMysqlDBName(url);
        query = String.format("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA='%s' AND TABLE_NAME IN %s", dbName, tables);
      } else if (url.startsWith(MetaStoreUtils.SQLITE_URL_PREFIX)) {
        query = String.format("SELECT COUNT(*) FROM sqlite_master "
                + "WHERE TYPE='table' AND NAME IN %s", tables);
      } else {
        throw new MetaStoreException("The jdbc url is not valid for SSM use.");
      }

      int num = 0;
      Statement s = conn.createStatement();
      ResultSet rs = s.executeQuery(query);
      if (rs.next()) {
        num = rs.getInt(1);
      }
      return num;
    } catch (Exception e) {
      throw new MetaStoreException(e);
    } finally {
      closeConnection(conn);
    }
  }

  public static void initializeDataBase(
      Connection conn) throws MetaStoreException {
    ArrayList<String> tableList = new ArrayList<>();
    for (String table: TABLESET) {
      tableList.add("DROP TABLE IF EXISTS " + table);
    }
    String deleteExistingTables[] = tableList.toArray(new String[tableList.size()]);
    String password = Hashing.sha512().hashString("ssm@123", StandardCharsets.UTF_8).toString();
    String createEmptyTables[] =
        new String[] {
          "CREATE TABLE access_count_table (\n"
              + "  table_name varchar(255) PRIMARY KEY,\n"
              + "  start_time bigint(20) NOT NULL,\n"
              + "  end_time bigint(20) NOT NULL\n"
              + ") ;",
          "CREATE TABLE blank_access_count_info (\n"
              + "  fid bigint(20) NOT NULL,\n"
              + "  count bigint(20) NOT NULL\n"
              + ");",
          "CREATE TABLE cached_file (\n"
              + "  fid bigint(20) NOT NULL,\n"
              + "  path varchar(1000) NOT NULL,\n"
              + "  from_time bigint(20) NOT NULL,\n"
              + "  last_access_time bigint(20) NOT NULL,\n"
              + "  accessed_num int(11) NOT NULL\n"
              + ");",
          "CREATE INDEX cached_file_fid_idx ON cached_file (fid);",
          "CREATE INDEX cached_file_path_idx ON cached_file (path);",
          "CREATE TABLE ec_policy (\n"
              + "  id tinyint(1) NOT NULL PRIMARY KEY,\n"
              + "  policy_name varchar(255) NOT NULL\n"
              + ");",
          "CREATE TABLE file (\n"
              + "  path varchar(1000) NOT NULL,\n"
              + "  fid bigint(20) NOT NULL,\n"
              + "  length bigint(20) DEFAULT NULL,\n"
              + "  block_replication smallint(6) DEFAULT NULL,\n"
              + "  block_size bigint(20) DEFAULT NULL,\n"
              + "  modification_time bigint(20) DEFAULT NULL,\n"
              + "  access_time bigint(20) DEFAULT NULL,\n"
              + "  is_dir tinyint(1) DEFAULT NULL,\n"
              + "  sid tinyint(4) DEFAULT NULL,\n"
              + "  owner varchar(255) DEFAULT NULL,\n"
              + "  owner_group varchar(255) DEFAULT NULL,\n"
              + "  permission smallint(6) DEFAULT NULL,\n"
              + "  ec_policy_id tinyint(1) DEFAULT NULL\n"
              + ");",
          "CREATE INDEX file_fid_idx ON file (fid);",
          "CREATE INDEX file_path_idx ON file (path);",
          "CREATE TABLE storage (\n"
              + "  type varchar(32) PRIMARY KEY,\n"
              + "  time_stamp bigint(20) DEFAULT NULL,\n"
              + "  capacity bigint(20) NOT NULL,\n"
              + "  free bigint(20) NOT NULL\n"
              + ");",
          "CREATE TABLE storage_hist (\n" // Keep this compatible with Table 'storage'
              + "  type varchar(64),\n"
              + "  time_stamp bigint(20) DEFAULT NULL,\n"
              + "  capacity bigint(20) NOT NULL,\n"
              + "  free bigint(20) NOT NULL\n"
              + ");",
          "CREATE INDEX type_idx ON storage_hist (type);",
          "CREATE INDEX time_stamp_idx ON storage_hist (time_stamp);",
          "CREATE TABLE storage_policy (\n"
              + "  sid tinyint(4) PRIMARY KEY,\n"
              + "  policy_name varchar(64) DEFAULT NULL\n"
              + ");",
          "INSERT INTO storage_policy VALUES ('0', 'UNDEF');",
          "INSERT INTO storage_policy VALUES ('2', 'COLD');",
          "INSERT INTO storage_policy VALUES ('5', 'WARM');",
          "INSERT INTO storage_policy VALUES ('7', 'HOT');",
          "INSERT INTO storage_policy VALUES ('10', 'ONE_SSD');",
          "INSERT INTO storage_policy VALUES ('12', 'ALL_SSD');",
          "INSERT INTO storage_policy VALUES ('15', 'LAZY_PERSIST');",
          "CREATE TABLE xattr (\n"
              + "  fid bigint(20) NOT NULL,\n"
              + "  namespace varchar(255) NOT NULL,\n"
              + "  name varchar(255) NOT NULL,\n"
              + "  value blob NOT NULL\n"
              + ");",
          "CREATE INDEX xattr_fid_idx ON xattr (fid);",
          "CREATE TABLE datanode_info (\n"
              + "  uuid varchar(64) PRIMARY KEY,\n"
              + "  hostname varchar(255) NOT NULL,\n"
              + // DatanodeInfo
              "  rpcAddress varchar(21) DEFAULT NULL,\n"
              + "  cache_capacity bigint(20) DEFAULT NULL,\n"
              + "  cache_used bigint(20) DEFAULT NULL,\n"
              + "  location varchar(255) DEFAULT NULL\n"
              + ");",
          "CREATE TABLE datanode_storage_info (\n"
              + "  uuid varchar(64) NOT NULL,\n"
              + "  sid tinyint(4) NOT NULL,\n"
              + // storage type
              "  state tinyint(4) NOT NULL,\n"
              + // DatanodeStorage.state
              "  storage_id varchar(64) NOT NULL,\n"
              + // StorageReport ...
              "  failed tinyint(1) DEFAULT NULL,\n"
              + "  capacity bigint(20) DEFAULT NULL,\n"
              + "  dfs_used bigint(20) DEFAULT NULL,\n"
              + "  remaining bigint(20) DEFAULT NULL,\n"
              + "  block_pool_used bigint(20) DEFAULT NULL\n"
              + ");",
          "CREATE TABLE rule (\n"
              + "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
              + "  name varchar(255) DEFAULT NULL,\n"
              + "  state tinyint(4) NOT NULL,\n"
              + "  rule_text varchar(4096) NOT NULL,\n"
              + "  submit_time bigint(20) NOT NULL,\n"
              + "  last_check_time bigint(20) DEFAULT NULL,\n"
              + "  checked_count int(11) NOT NULL,\n"
              + "  generated_cmdlets int(11) NOT NULL\n"
              + ");",
          "CREATE TABLE cmdlet (\n"
              + "  cid INTEGER PRIMARY KEY,\n"
              + "  rid INTEGER NOT NULL,\n"
              + "  aids varchar(4096) NOT NULL,\n"
              + "  state tinyint(4) NOT NULL,\n"
              + "  parameters varchar(4096) NOT NULL,\n"
              + "  generate_time bigint(20) NOT NULL,\n"
              + "  state_changed_time bigint(20) NOT NULL\n"
              + ");",
          "CREATE TABLE action (\n"
              + "  aid INTEGER PRIMARY KEY,\n"
              + "  cid INTEGER NOT NULL,\n"
              + "  action_name varchar(4096) NOT NULL,\n"
              + "  args text NOT NULL,\n"
              + "  result mediumtext NOT NULL,\n"
              + "  log longtext NOT NULL,\n"
              + "  successful tinyint(4) NOT NULL,\n"
              + "  create_time bigint(20) NOT NULL,\n"
              + "  finished tinyint(4) NOT NULL,\n"
              + "  finish_time bigint(20) NOT NULL,\n"
              + "  exec_host varchar(255),\n"
              + "  progress float NOT NULL\n"
              + ");",
          "CREATE TABLE file_diff (\n"
              + "  did INTEGER PRIMARY KEY AUTOINCREMENT,\n"
              + "  rid INTEGER NOT NULL,\n"
              + "  diff_type varchar(4096) NOT NULL,\n"
              + "  src varchar(1000) NOT NULL,\n"
              + "  parameters varchar(4096) NOT NULL,\n"
              + "  state tinyint(4) NOT NULL,\n"
              + "  create_time bigint(20) NOT NULL\n"
              + ");",
          "CREATE INDEX file_diff_idx ON file_diff (src);",
          "CREATE TABLE global_config (\n"
              + " cid INTEGER PRIMARY KEY AUTOINCREMENT,\n"
              + " property_name varchar(512) NOT NULL UNIQUE,\n"
              + " property_value varchar(3072) NOT NULL\n"
              + ");",
          "CREATE TABLE cluster_config (\n"
              + " cid INTEGER PRIMARY KEY AUTOINCREMENT,\n"
              + " node_name varchar(512) NOT NULL UNIQUE,\n"
              + " config_path varchar(3072) NOT NULL\n"
              + ");",
          "CREATE TABLE sys_info (\n"
              + "  property varchar(512) PRIMARY KEY,\n"
              + "  value varchar(4096) NOT NULL\n"
              + ");",
          "CREATE TABLE user_info (\n"
              + "  user_name varchar(20) PRIMARY KEY,\n"
              + "  user_password varchar(256) NOT NULL\n"
              + ");",
          "INSERT INTO user_info VALUES('admin','" + password + "');",
          "CREATE TABLE cluster_info (\n"
              + "  cid INTEGER PRIMARY KEY AUTOINCREMENT,\n"
              + "  name varchar(512) NOT NULL UNIQUE,\n"
              + "  url varchar(4096) NOT NULL,\n"
              + "  conf_path varchar(4096) NOT NULL,\n"
              + "  state varchar(64) NOT NULL,\n"
              + // ClusterState
              "  type varchar(64) NOT NULL\n"
              + // ClusterType
              ");",
          "CREATE TABLE backup_file (\n"
              + " rid bigint(20) NOT NULL,\n"
              + " src varchar(4096) NOT NULL,\n"
              + " dest varchar(4096) NOT NULL,\n"
              + " period bigint(20) NOT NULL\n"
              + ");",
          "CREATE INDEX backup_file_rid_idx ON backup_file (rid);",
          "CREATE TABLE file_state (\n"
              + " path varchar(512) PRIMARY KEY,\n"
              + " type tinyint(4) NOT NULL,\n"
              + " stage tinyint(4) NOT NULL\n"
              + ");",
          "CREATE TABLE compression_file (\n"
              + " path varchar(512) PRIMARY KEY,\n"
              + " buffer_size int(11) NOT NULL,\n"
              + " compression_impl varchar(64) NOT NULL,\n"
              + " original_length bigint(20) NOT NULL,\n"
              + " compressed_length bigint(20) NOT NULL,\n"
              + " originalPos text NOT NULL,\n"
              + " compressedPos text NOT NULL\n"
              + ");",
          "CREATE TABLE small_file (\n"
              + "path varchar(1000) NOT NULL PRIMARY KEY,\n"
              + "container_file_path varchar(4096) NOT NULL,\n"
              + "offset bigint(20) NOT NULL,\n"
              + "length bigint(20) NOT NULL\n"
              + ");"
        };
    try {
      for (String s : deleteExistingTables) {
        // Drop table if exists
        LOG.debug(s);
        executeSql(conn, s);
      }
      // Handle mysql related features
      String url = conn.getMetaData().getURL();
      boolean mysql = url.startsWith(MetaStoreUtils.MYSQL_URL_PREFIX);
      boolean mysqlOldRelease = false;
      if (mysql) {
        // Mysql version number
        double mysqlVersion =
            conn.getMetaData().getDatabaseMajorVersion()
                + conn.getMetaData().getDatabaseMinorVersion() * 0.1;
        LOG.debug("Mysql Version Number {}", mysqlVersion);
        if (mysqlVersion < 5.5) {
          LOG.error("Required Mysql version >= 5.5, but current is " + mysqlVersion);
          throw new MetaStoreException("Mysql version " + mysqlVersion + " is below requirement!");
        } else if (mysqlVersion < 5.7 && mysqlVersion >= 5.5) {
          mysqlOldRelease = true;
        }
      }
      if (mysqlOldRelease) {
        // Enable dynamic file format to avoid index length limit 767
        executeSql(conn, "SET GLOBAL innodb_file_format=barracuda;");
        executeSql(conn, "SET GLOBAL innodb_file_per_table=true;");
        executeSql(conn, "SET GLOBAL innodb_large_prefix = ON;");
      }
      for (String s : createEmptyTables) {
        // Solve mysql and sqlite sql difference
        s = sqlCompatibility(mysql, mysqlOldRelease, s);
        LOG.debug(s);
        executeSql(conn, s);
      }
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
  }

  /**
   * * Solve SQL compatibility problem caused by mysql and sqlite. * Note that mysql 5.6 or earlier
   * cannot support index length larger than 767. * Meanwhile, sqlite's keywords are a little
   * different from mysql.
   *
   * @param mysql boolean
   * @param mysqlOldRelease boolean mysql version is earlier than 5.6
   * @param sql String sql
   * @return converted sql
   */
  private static String sqlCompatibility(boolean mysql, boolean mysqlOldRelease, String sql) {
    if (mysql) {
      // path/src index should be set to less than 767
      // to avoid "Specified key was too long" in
      // Mysql 5.6 or previous version
      if (mysqlOldRelease) {
        // Fix index size 767 in mysql 5.6 or previous version
        int maxLong = 767 / characterTakeUpBytes;
        if (sql.startsWith("CREATE INDEX")
            && (sql.contains("path") || sql.contains("src"))) {
          // Index longer than maxLong
          sql = sql.replace(");", "(" + maxLong + "));");
        } else if (sql.contains("PRIMARY KEY") || sql.contains("UNIQUE")) {
          // Primary key longer than maxLong
          Pattern p = Pattern.compile("(\\d{3,})(.{2,15})(PRIMARY|UNIQUE)");
          Matcher m = p.matcher(sql);
          if (m.find()) {
            if (Integer.valueOf(m.group(1)) > maxLong) {
              // Make this table dynamic
              sql = sql.replace(");", ") ROW_FORMAT=DYNAMIC ENGINE=INNODB;");
              LOG.debug(sql);
            }
          }
        }
      }
      // Replace AUTOINCREMENT with AUTO_INCREMENT
      if (sql.contains("AUTOINCREMENT")) {
        sql = sql.replace("AUTOINCREMENT", "AUTO_INCREMENT");
      }
    }
    return sql;
  }

  public static void executeSql(Connection conn, String sql)
      throws MetaStoreException {
    try {
      Statement s = conn.createStatement();
      s.execute(sql);
    } catch (Exception e) {
      LOG.error("SQL execution error " + sql);
      throw new MetaStoreException(e);
    }
  }

  public static boolean supportsBatchUpdates(Connection conn) {
    try {
      return conn.getMetaData().supportsBatchUpdates();
    } catch (Exception e) {
      return false;
    }
  }

  public static void formatDatabase(SmartConf conf) throws MetaStoreException {
    getDBAdapter(conf).formatDataBase();
  }

  public static void checkTables(SmartConf conf) throws MetaStoreException {
    getDBAdapter(conf).checkTables();
  }

  public static String getMysqlDBName(String url) throws SQLException {
    NonRegisteringDriver nonRegisteringDriver = new NonRegisteringDriver();
    Properties properties = nonRegisteringDriver.parseURL(url, null);
    return properties.getProperty(nonRegisteringDriver.DBNAME_PROPERTY_KEY);
  }

  public static MetaStore getDBAdapter(
          SmartConf conf) throws MetaStoreException {
    URL pathUrl = ClassLoader.getSystemResource("");
    String path = pathUrl.getPath();

    characterTakeUpBytes = conf.getInt(
      SmartConfKeys.SMART_METASTORE_CHARACTER_TAKEUP_BYTES_KEY,
      SmartConfKeys.SMART_METASTORE_CHARACTER_TAKEUP_BYTES_DEFAULT);

    String fileName = "druid.xml";
    String expectedCpPath = path + fileName;
    LOG.info("Expected DB connection pool configuration path = "
            + expectedCpPath);
    File cpConfigFile = new File(expectedCpPath);
    if (cpConfigFile.exists()) {
      LOG.info("Using pool configure file: " + expectedCpPath);
      Properties p = new Properties();
      try {
        p.loadFromXML(new FileInputStream(cpConfigFile));

        String url = conf.get(SmartConfKeys.SMART_METASTORE_DB_URL_KEY);
        if (url != null) {
          p.setProperty("url", url);
        }

        String purl = p.getProperty("url");
        if (purl == null || purl.length() == 0) {
          purl = getDefaultSqliteDB(); // For testing
          p.setProperty("url", purl);
          LOG.warn("Database URL not specified, using " + purl);
        }

        if (purl.startsWith(MetaStoreUtils.MYSQL_URL_PREFIX)) {
          String dbName = getMysqlDBName(purl);
          for (String name : DB_NAME_NOT_ALLOWED) {
            if (dbName.equals(name)) {
              throw new MetaStoreException(
                      String.format(
                              "The database %s in mysql is for DB system use, "
                                      + "please appoint other database in druid.xml.",
                              name));
            }
          }
        }

        try {
          String pw = conf
            .getPasswordFromHadoop(SmartConfKeys.SMART_METASTORE_PASSWORD);
          if (pw != null && pw != "") {
            p.setProperty("password", pw);
          }
        } catch (IOException e) {
          LOG.info("Can not get metastore password from hadoop provision credentials,"
            + " use the one configured in druid.xml .");
        }

        for (String key : p.stringPropertyNames()) {
          if (key.equals("password")) {
            LOG.info("\t" + key + " = **********");
          } else {
            LOG.info("\t" + key + " = " + p.getProperty(key));
          }
        }
        return new MetaStore(new DruidPool(p));
      } catch (Exception e) {
        if (e instanceof InvalidPropertiesFormatException) {
          throw new MetaStoreException(
                  "Malformat druid.xml, please check the file.", e);
        } else {
          throw new MetaStoreException(e);
        }
      }
    } else {
      LOG.info("DB connection pool config file " + expectedCpPath
              + " NOT found.");
    }
    // Get Default configure from druid-template.xml
    fileName = "druid-template.xml";
    expectedCpPath = path + fileName;
    LOG.info("Expected DB connection pool configuration path = "
            + expectedCpPath);
    cpConfigFile = new File(expectedCpPath);
    LOG.info("Using pool configure file: " + expectedCpPath);
    Properties p = new Properties();
    try {
      p.loadFromXML(new FileInputStream(cpConfigFile));
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
    String url = conf.get(SmartConfKeys.SMART_METASTORE_DB_URL_KEY);
    if (url != null) {
      p.setProperty("url", url);
    }
    for (String key : p.stringPropertyNames()) {
      LOG.info("\t" + key + " = " + p.getProperty(key));
    }
    return new MetaStore(new DruidPool(p));
  }

  public static Integer getKey(Map<Integer, String> map, String value) {
    for (Integer key : map.keySet()) {
      if (map.get(key).equals(value)) {
        return key;
      }
    }
    return null;
  }

  /**
   * Retrieve table column names.
   *
   * @param conn
   * @param tableName
   * @return
   * @throws MetaStoreException
   */
  public static List<String> getTableColumns(Connection conn, String tableName)
    throws MetaStoreException {
    List<String> ret = new ArrayList<>();
    try {
      ResultSet res = conn.getMetaData().getColumns(null, null, tableName, null);
      while (res.next()) {
        ret.add(res.getString("COLUMN_NAME"));
      }
      return ret;
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
  }

  /**
   * This default behavior provided here is mainly for convenience.
   *
   * @return
   */
  private static String getDefaultSqliteDB() throws MetaStoreException {
    String absFilePath = System.getProperty("user.home")
        + "/smart-test-default.db";
    File file = new File(absFilePath);
    if (file.exists()) {
      return MetaStoreUtils.SQLITE_URL_PREFIX + absFilePath;
    }
    try {
      Connection conn = MetaStoreUtils.createSqliteConnection(absFilePath);
      MetaStoreUtils.initializeDataBase(conn);
      conn.close();
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
    return MetaStoreUtils.SQLITE_URL_PREFIX + absFilePath;
  }

  public static void dropAllTablesSqlite(
      Connection conn) throws MetaStoreException {
    try {
      Statement s = conn.createStatement();
      ResultSet rs = s.executeQuery("SELECT tbl_name FROM sqlite_master;");
      List<String> list = new ArrayList<>();
      while (rs.next()) {
        list.add(rs.getString(1));
      }
      for (String tb : list) {
        if (!"sqlite_sequence".equals(tb)) {
          s.execute("DROP TABLE IF EXISTS '" + tb + "';");
        }
      }
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
  }

  public static void dropAllTablesMysql(Connection conn,
      String url) throws MetaStoreException {
    try {
      Statement stat = conn.createStatement();
      String dbName = getMysqlDBName(url);
      LOG.info("Drop All tables of Current DBname: " + dbName);
      ResultSet rs = stat.executeQuery("SELECT TABLE_NAME FROM "
          + "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '" + dbName + "';");
      List<String> tbList = new ArrayList<>();
      while (rs.next()) {
        tbList.add(rs.getString(1));
      }
      for (String tb : tbList) {
        LOG.info(tb);
        stat.execute("DROP TABLE IF EXISTS " + tb + ";");
      }
    } catch (Exception e) {
      throw new MetaStoreException(e);
    }
  }
}

