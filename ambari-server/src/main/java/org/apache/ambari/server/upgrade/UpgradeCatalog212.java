/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.upgrade;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.persist.Transactional;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.DBAccessor.DBColumnInfo;
import org.apache.ambari.server.orm.dao.ClusterDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.entities.ClusterEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;


/**
 * Upgrade catalog for version 2.1.2.
 */
public class UpgradeCatalog212 extends AbstractUpgradeCatalog {

  public static final String UPGRADE_PACKAGE_COL = "upgrade_package";
  public static final String UPGRADE_TYPE_COL = "upgrade_type";
  public static final String UPGRADE_TABLE = "upgrade";
  public static final String REPO_VERSION_TABLE = "repo_version";

  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog212.class);

  @Inject
  private RepositoryVersionDAO repositoryVersionDAO;

  @Inject
  private ClusterDAO clusterDAO;


  // ----- Constructors ------------------------------------------------------

  /**
   * Don't forget to register new UpgradeCatalogs in {@link org.apache.ambari.server.upgrade.SchemaUpgradeHelper.UpgradeHelperModule#configure()}
   *
   * @param injector Guice injector to track dependencies and uses bindings to inject them.
   */
  @Inject
  public UpgradeCatalog212(Injector injector) {
    super(injector);
    this.injector = injector;
  }

  // ----- UpgradeCatalog ----------------------------------------------------

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTargetVersion() {
    return "2.1.2";
  }

  // ----- AbstractUpgradeCatalog --------------------------------------------

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSourceVersion() {
    return "2.1.1";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDDLUpdates() throws AmbariException, SQLException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executePreDMLUpdates() throws AmbariException, SQLException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDMLUpdates() throws AmbariException, SQLException {
    executeStackUpgradeDDLUpdates();
  }

  // ----- UpgradeCatalog212 --------------------------------------------

  /**
   * Move the upgrade_package column from the repo_version table to the upgrade table as follows,
   * add column upgrade_package to upgrade table as String 255 and nullable
   * populate column in the upgrade table
   * drop the column in the repo_version table
   * make the column in the upgrade table non-nullable.
   * This has to be called as part of DML and not DDL since the persistence service has to be started.
   * @throws AmbariException
   * @throws SQLException
   */
  @Transactional
  private void executeStackUpgradeDDLUpdates() throws SQLException, AmbariException {
    final Configuration.DatabaseType databaseType = configuration.getDatabaseType();

    // Add columns
    if (!dbAccessor.tableHasColumn(UPGRADE_TABLE, UPGRADE_PACKAGE_COL)) {
      LOG.info("Adding upgrade_package column to upgrade table.");
      dbAccessor.addColumn(UPGRADE_TABLE, new DBColumnInfo(UPGRADE_PACKAGE_COL, String.class, 255, null, true));
    }
    if (!dbAccessor.tableHasColumn(UPGRADE_TABLE, UPGRADE_TYPE_COL)) {
      LOG.info("Adding upgrade_type column to upgrade table.");
      dbAccessor.addColumn(UPGRADE_TABLE, new DBColumnInfo(UPGRADE_TYPE_COL, String.class, 32, null, true));
    }

    // Populate values in upgrade table.
    boolean success = this.populateUpgradeTable();

    if (!success) {
      throw new AmbariException("Errors found while populating the upgrade table with values for columns upgrade_type and upgrade_package.");
    }

    if (dbAccessor.tableHasColumn(REPO_VERSION_TABLE, UPGRADE_PACKAGE_COL)) {
      LOG.info("Dropping upgrade_package column from repo_version table.");
      dbAccessor.dropColumn(REPO_VERSION_TABLE, UPGRADE_PACKAGE_COL);

      // Now, make the added column non-nullable
      // Make the hosts id non-null after all the values are populated
      LOG.info("Making upgrade_package column in the upgrade table non-nullable.");
      if (databaseType == Configuration.DatabaseType.DERBY) {
        // This is a workaround for UpgradeTest.java unit test
        dbAccessor.executeQuery("ALTER TABLE " + UPGRADE_TABLE + " ALTER column " + UPGRADE_PACKAGE_COL + " NOT NULL");
      } else {
        dbAccessor.alterColumn(UPGRADE_TABLE, new DBColumnInfo(UPGRADE_PACKAGE_COL, String.class, 255, null, false));
      }
    }

    if (dbAccessor.tableHasColumn(REPO_VERSION_TABLE, UPGRADE_TYPE_COL)) {
      // Now, make the added column non-nullable
      // Make the hosts id non-null after all the values are populated
      LOG.info("Making upgrade_type column in the upgrade table non-nullable.");
      if (databaseType == Configuration.DatabaseType.DERBY) {
        // This is a workaround for UpgradeTest.java unit test
        dbAccessor.executeQuery("ALTER TABLE " + UPGRADE_TABLE + " ALTER column " + UPGRADE_TYPE_COL + " NOT NULL");
      } else {
        dbAccessor.alterColumn(UPGRADE_TABLE, new DBColumnInfo(UPGRADE_TYPE_COL, String.class, 32, null, false));
      }
    }
  }

  /**
   * Populate the upgrade table with values for the columns upgrade_type and upgrade_package.
   * The upgrade_type will default to {@code org.apache.ambari.server.state.stack.upgrade.UpgradeType.ROLLING}
   * whereas the upgrade_package will be calculated.
   * @return {@code} true on success, and {@code} false otherwise.
   */
  private boolean populateUpgradeTable() {
    boolean success = true;
    Statement statement = null;
    ResultSet rs = null;
    try {
      statement = dbAccessor.getConnection().createStatement();
      if (statement != null) {
        // Need to use SQL since the schema is changing and some of the columns have not yet been added..
        rs = statement.executeQuery("SELECT upgrade_id, cluster_id, from_version, to_version, direction, upgrade_package, upgrade_type FROM upgrade");
        if (rs != null) {
          try {
            while (rs.next()) {
              final long upgradeId = rs.getLong("upgrade_id");
              final long clusterId = rs.getLong("cluster_id");
              final String fromVersion = rs.getString("from_version");
              final String toVersion = rs.getString("to_version");
              final Direction direction = Direction.valueOf(rs.getString("direction"));
              // These two values are likely null.
              String upgradePackage = rs.getString("upgrade_package");
              String upgradeType = rs.getString("upgrade_type");

              LOG.info(MessageFormat.format("Populating rows for the upgrade table record with " +
                  "upgrade_id: {0}, cluster_id: {1}, from_version: {2}, to_version: {3}, direction: {4}",
                  upgradeId, clusterId, fromVersion, toVersion, direction));

              // Set all upgrades that have been done so far to type "rolling"
              if (StringUtils.isEmpty(upgradeType)) {
                LOG.info("Updating the record's upgrade_type to " + UpgradeType.ROLLING);
                dbAccessor.executeQuery("UPDATE upgrade SET upgrade_type = '" + UpgradeType.ROLLING + "' WHERE upgrade_id = " + upgradeId);
              }

              if (StringUtils.isEmpty(upgradePackage)) {
                String version = null;
                StackEntity stack = null;

                if (direction == Direction.UPGRADE) {
                  version = toVersion;
                } else if (direction == Direction.DOWNGRADE) {
                  // TODO AMBARI-12698, this is going to be a problem.
                  // During a downgrade, the "to_version" is overwritten to the source version, but the "from_version"
                  // doesn't swap. E.g.,
                  //  upgrade_id | from_version |  to_version  | direction
                  // ------------+--------------+--------------+----------
                  //           1 | 2.2.6.0-2800 | 2.3.0.0-2557 | UPGRADE
                  //           2 | 2.2.6.0-2800 | 2.2.6.0-2800 | DOWNGRADE
                  version = fromVersion;
                }

                ClusterEntity cluster = clusterDAO.findById(clusterId);

                if (null != cluster) {
                  stack = cluster.getDesiredStack();
                  upgradePackage = this.calculateUpgradePackage(stack, version);
                } else {
                  LOG.error("Could not find a cluster with cluster_id " + clusterId);
                }

                if (!StringUtils.isEmpty(upgradePackage)) {
                  LOG.info("Updating the record's upgrade_package to " + upgradePackage);
                  dbAccessor.executeQuery("UPDATE upgrade SET upgrade_package = '" + upgradePackage + "' WHERE upgrade_id = " + upgradeId);
                } else {
                  success = false;
                  LOG.error("Unable to populate column upgrade_package for record in table upgrade with id " + upgradeId);
                }
              }
            }
          } catch (Exception e) {
            success = false;
            e.printStackTrace();
            LOG.error("Unable to populate the upgrade_type and upgrade_package columns of the upgrade table. " + e);
          }
        }
      }
    } catch (Exception e) {
      success = false;
      e.printStackTrace();
      LOG.error("Failed to retrieve records from the upgrade table to populate the upgrade_type and upgrade_package columns. Exception: " + e);
    } finally {
      try {
        if (rs != null) {
          rs.close();
        }
        if (statement != null) {
          statement.close();
        }
      } catch (SQLException e) {
        ;
      }
    }
    return success;
  }

  /**
   * Find the single Repo Version for the given stack and version, and return its upgrade_package column.
   * Because the upgrade_package column is going to be removed from this entity, must use raw SQL
   * instead of the entity class.
   * @param stack Stack
   * @param version Stack version
   * @return The value of the upgrade_package column, or null if not found.
   */

  private String calculateUpgradePackage(StackEntity stack, String version) {
    String upgradePackage = null;
    // Find the corresponding repo_version, and extract its upgrade_package
    if (null != version && null != stack) {
      RepositoryVersionEntity repoVersion = repositoryVersionDAO.findByStackNameAndVersion(stack.getStackName(), version);

      Statement statement = null;
      ResultSet rs = null;
      try {
        statement = dbAccessor.getConnection().createStatement();
        if (statement != null) {
          // Need to use SQL since the schema is changing and the entity will no longer have the upgrade_package column.
          rs = statement.executeQuery("SELECT upgrade_package FROM repo_version WHERE repo_version_id = " + repoVersion.getId());
          if (rs != null && rs.next()) {
            upgradePackage = rs.getString("upgrade_package");
          }
        }
      } catch (Exception e) {
        LOG.error("Failed to retrieve upgrade_package for repo_version record with id " + repoVersion.getId() + ". Exception: " + e.getMessage());
      } finally {
        try {
          if (rs != null) {
            rs.close();
          }
          if (statement != null) {
            statement.close();
          }
        } catch (SQLException e) {
          ;
        }
      }
    }
    return upgradePackage;
  }
}
