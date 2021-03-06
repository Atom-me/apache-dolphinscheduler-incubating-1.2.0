/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dolphinscheduler.dao.upgrade;

import com.alibaba.druid.pool.DruidDataSource;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.DbType;
import org.apache.dolphinscheduler.common.utils.ConnectionUtils;
import org.apache.dolphinscheduler.common.utils.SchemaUtils;
import org.apache.dolphinscheduler.common.utils.ScriptRunner;
import org.apache.dolphinscheduler.dao.AbstractBaseDao;
import org.apache.dolphinscheduler.dao.datasource.ConnectionFactory;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;

public abstract class UpgradeDao extends AbstractBaseDao {

    public static final Logger logger = LoggerFactory.getLogger(UpgradeDao.class);
    private static final String T_VERSION_NAME = "t_escheduler_version";
    private static final String T_NEW_VERSION_NAME = "t_ds_version";
    private static final String rootDir = System.getProperty("user.dir");
    protected static final DruidDataSource dataSource = getDataSource();
    private static final DbType dbType = getCurrentDbType();

    @Override
    protected void init() {

    }

    /**
     * get datasource
     * @return DruidDataSource
     */
    public static DruidDataSource getDataSource(){
        DruidDataSource dataSource = ConnectionFactory.getDataSource();
        dataSource.setInitialSize(2);
        dataSource.setMinIdle(2);
        dataSource.setMaxActive(2);

        return dataSource;
    }

    /**
     * get db type
     * @return dbType
     */
    public static DbType getDbType(){
        return dbType;
    }

    /**
     * get current dbType
     * @return
     */
    private static DbType getCurrentDbType(){
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            String name = conn.getMetaData().getDatabaseProductName().toUpperCase();
            return DbType.valueOf(name);
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
            return null;
        }finally {
            ConnectionUtils.releaseResource(null, null, conn);
        }
    }

    /**
     * init schema
     */
    public void initSchema(){
        DbType dbType = getDbType();
        String initSqlPath = "";
        if (dbType != null) {
            switch (dbType) {
                case MYSQL:
                    initSqlPath = "/sql/create/release-1.0.0_schema/mysql/";
                    initSchema(initSqlPath);
                    break;
                case POSTGRESQL:
                    initSqlPath = "/sql/create/release-1.2.0_schema/postgresql/";
                    initSchema(initSqlPath);
                    break;
                default:
                    logger.error("not support sql type: {},can't upgrade", dbType);
                    throw new IllegalArgumentException("not support sql type,can't upgrade");
            }
        }
    }


    /**
     * init scheam
     * @param initSqlPath initSqlPath
     */
    public void initSchema(String initSqlPath) {

        // Execute the dolphinscheduler DDL, it cannot be rolled back
        runInitDDL(initSqlPath);

        // Execute the dolphinscheduler DML, it can be rolled back
        runInitDML(initSqlPath);

    }

    /**
     * run DML
     * @param initSqlPath initSqlPath
     */
    private void runInitDML(String initSqlPath) {
        Connection conn = null;
        if (StringUtils.isEmpty(rootDir)) {
            throw new RuntimeException("Environment variable user.dir not found");
        }
        String mysqlSQLFilePath = rootDir + initSqlPath + "dolphinscheduler_dml.sql";
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // Execute the dolphinscheduler_dml.sql script to import related data of dolphinscheduler
            ScriptRunner initScriptRunner = new ScriptRunner(conn, false, true);
            Reader initSqlReader = new FileReader(new File(mysqlSQLFilePath));
            initScriptRunner.runScript(initSqlReader);

            conn.commit();
        } catch (IOException e) {
            try {
                conn.rollback();
            } catch (SQLException e1) {
                logger.error(e1.getMessage(),e1);
            }
            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException e1) {
                logger.error(e1.getMessage(),e1);
            }
            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } finally {
            ConnectionUtils.releaseResource(null, null, conn);

        }

    }

    /**
     * run DDL
     * @param initSqlPath initSqlPath
     */
    private void runInitDDL(String initSqlPath) {
        Connection conn = null;
        if (StringUtils.isEmpty(rootDir)) {
            throw new RuntimeException("Environment variable user.dir not found");
        }
        //String mysqlSQLFilePath = rootDir + "/sql/create/release-1.0.0_schema/mysql/dolphinscheduler_ddl.sql";
        String mysqlSQLFilePath = rootDir + initSqlPath + "dolphinscheduler_ddl.sql";
        try {
            conn = dataSource.getConnection();
            // Execute the dolphinscheduler_ddl.sql script to create the table structure of dolphinscheduler
            ScriptRunner initScriptRunner = new ScriptRunner(conn, true, true);
            Reader initSqlReader = new FileReader(new File(mysqlSQLFilePath));
            initScriptRunner.runScript(initSqlReader);

        } catch (IOException e) {

            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } catch (Exception e) {

            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } finally {
            ConnectionUtils.releaseResource(null, null, conn);

        }

    }

    /**
     * determines whether a table exists
     * @param tableName tableName
     * @return if table exist return true???else return false
     */
    public abstract boolean isExistsTable(String tableName);

    /**
     * determines whether a field exists in the specified table
     * @param tableName tableName
     * @param columnName columnName
     * @return  if column name exist return true???else return false
     */
    public abstract boolean isExistsColumn(String tableName,String columnName);


    /**
     * get current version
     * @param versionName versionName
     * @return version
     */
    public String getCurrentVersion(String versionName) {
        String sql = String.format("select version from %s",versionName);
        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement pstmt = null;
        String version = null;
        try {
            conn = dataSource.getConnection();
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                version = rs.getString(1);
            }

            return version;

        } catch (SQLException e) {
            logger.error(e.getMessage(),e);
            throw new RuntimeException("sql: " + sql, e);
        } finally {
            ConnectionUtils.releaseResource(rs, pstmt, conn);
        }
    }


    /**
     * upgrade DolphinScheduler
     * @param schemaDir schema dir
     */
    public void upgradeDolphinScheduler(String schemaDir) {

        upgradeDolphinSchedulerDDL(schemaDir);

        upgradeDolphinSchedulerDML(schemaDir);

    }

    /**
     * upgradeDolphinScheduler DML
     * @param schemaDir schemaDir
     */
    private void upgradeDolphinSchedulerDML(String schemaDir) {
        String schemaVersion = schemaDir.split("_")[0];
        if (StringUtils.isEmpty(rootDir)) {
            throw new RuntimeException("Environment variable user.dir not found");
        }
        String sqlFilePath = MessageFormat.format("{0}/sql/upgrade/{1}/{2}/dolphinscheduler_dml.sql",rootDir,schemaDir,getDbType().name().toLowerCase());
        logger.info("sqlSQLFilePath"+sqlFilePath);
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            // Execute the upgraded dolphinscheduler dml
            ScriptRunner scriptRunner = new ScriptRunner(conn, false, true);
            Reader sqlReader = new FileReader(new File(sqlFilePath));
            scriptRunner.runScript(sqlReader);
            if (isExistsTable(T_VERSION_NAME)) {
                // Change version in the version table to the new version
                String upgradeSQL = String.format("update %s set version = ?",T_VERSION_NAME);
                pstmt = conn.prepareStatement(upgradeSQL);
                pstmt.setString(1, schemaVersion);
                pstmt.executeUpdate();
            }else if (isExistsTable(T_NEW_VERSION_NAME)) {
                // Change version in the version table to the new version
                String upgradeSQL = String.format("update %s set version = ?",T_NEW_VERSION_NAME);
                pstmt = conn.prepareStatement(upgradeSQL);
                pstmt.setString(1, schemaVersion);
                pstmt.executeUpdate();
            }
            conn.commit();
        } catch (FileNotFoundException e) {
            try {
                conn.rollback();
            } catch (SQLException e1) {
                logger.error(e1.getMessage(),e1);
            }
            logger.error(e.getMessage(),e);
            throw new RuntimeException("sql file not found ", e);
        } catch (IOException e) {
            try {
                conn.rollback();
            } catch (SQLException e1) {
                logger.error(e1.getMessage(),e1);
            }
            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException e1) {
                logger.error(e1.getMessage(),e1);
            }
            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException e1) {
                logger.error(e1.getMessage(),e1);
            }
            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } finally {
            ConnectionUtils.releaseResource(null, pstmt, conn);
        }

    }

    /**
     * upgradeDolphinScheduler DDL
     * @param schemaDir schemaDir
     */
    private void upgradeDolphinSchedulerDDL(String schemaDir) {
        if (StringUtils.isEmpty(rootDir)) {
            throw new RuntimeException("Environment variable user.dir not found");
        }
        String sqlFilePath = MessageFormat.format("{0}/sql/upgrade/{1}/{2}/dolphinscheduler_ddl.sql",rootDir,schemaDir,getDbType().name().toLowerCase());
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dataSource.getConnection();
            String dbName = conn.getCatalog();
            logger.info(dbName);
            conn.setAutoCommit(true);
            // Execute the dolphinscheduler ddl.sql for the upgrade
            ScriptRunner scriptRunner = new ScriptRunner(conn, true, true);
            Reader sqlReader = new FileReader(new File(sqlFilePath));
            scriptRunner.runScript(sqlReader);

        } catch (FileNotFoundException e) {

            logger.error(e.getMessage(),e);
            throw new RuntimeException("sql file not found ", e);
        } catch (IOException e) {

            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } catch (SQLException e) {

            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } catch (Exception e) {

            logger.error(e.getMessage(),e);
            throw new RuntimeException(e.getMessage(),e);
        } finally {
            ConnectionUtils.releaseResource(null, pstmt, conn);
        }

    }


    /**
     * update version
     * @param version version
     */
    public void updateVersion(String version) {
        // Change version in the version table to the new version
        String versionName = T_VERSION_NAME;
        if(!SchemaUtils.isAGreatVersion("1.2.0" , version)){
            versionName = "t_ds_version";
        }
        String upgradeSQL = String.format("update %s set version = ?",versionName);
        PreparedStatement pstmt = null;
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            pstmt = conn.prepareStatement(upgradeSQL);
            pstmt.setString(1, version);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            logger.error(e.getMessage(),e);
            throw new RuntimeException("sql: " + upgradeSQL, e);
        } finally {
            ConnectionUtils.releaseResource(null, pstmt, conn);
        }

    }

}
