/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.zookeeper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.DeserializationException;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ZooKeeperProtos;
import org.apache.zookeeper.KeeperException;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Helper class for table state tracking for use by {@link AssignmentManager}.
 * Reads, caches and sets state up in zookeeper.  If multiple read/write
 * clients, will make for confusion.  Read-only clients other than
 * AssignmentManager interested in learning table state can use the
 * read-only utility methods {@link #isEnabledTable(ZooKeeperWatcher, String)}
 * and {@link #isDisabledTable(ZooKeeperWatcher, String)}.
 * 
 * <p>To save on trips to the zookeeper ensemble, internally we cache table
 * state.
 */
@InterfaceAudience.Private
public class ZKTable {
  // A znode will exist under the table directory if it is in any of the
  // following states: {@link TableState#ENABLING} , {@link TableState#DISABLING},
  // or {@link TableState#DISABLED}.  If {@link TableState#ENABLED}, there will
  // be no entry for a table in zk.  Thats how it currently works.

  private static final Log LOG = LogFactory.getLog(ZKTable.class);
  private final ZooKeeperWatcher watcher;

  /**
   * Cache of what we found in zookeeper so we don't have to go to zk ensemble
   * for every query.  Synchronize access rather than use concurrent Map because
   * synchronization needs to span query of zk.
   */
  private final Map<String, ZooKeeperProtos.Table.State> cache =
    new HashMap<String, ZooKeeperProtos.Table.State>();

  // TODO: Make it so always a table znode. Put table schema here as well as table state.
  // Have watcher on table znode so all are notified of state or schema change.

  public ZKTable(final ZooKeeperWatcher zkw) throws KeeperException {
    super();
    this.watcher = zkw;
    populateTableStates();
  }

  /**
   * Gets a list of all the tables set as disabled in zookeeper.
   * @throws KeeperException
   */
  private void populateTableStates()
  throws KeeperException {
    synchronized (this.cache) {
      List<String> children = ZKUtil.listChildrenNoWatch(this.watcher, this.watcher.tableZNode);
      if (children == null) return;
      for (String child: children) {
        ZooKeeperProtos.Table.State state = getTableState(this.watcher, child);
        if (state != null) this.cache.put(child, state);
      }
    }
  }

  /**
   * @param zkw
   * @param child
   * @return Null or {@link TableState} found in znode.
   * @throws KeeperException
   */
  private static ZooKeeperProtos.Table.State getTableState(final ZooKeeperWatcher zkw,
      final String child)
  throws KeeperException {
    String znode = ZKUtil.joinZNode(zkw.tableZNode, child);
    byte [] data = ZKUtil.getData(zkw, znode);
    if (data == null || data.length <= 0) return ZooKeeperProtos.Table.State.ENABLED;
    try {
      ProtobufUtil.expectPBMagicPrefix(data);
      ZooKeeperProtos.Table.Builder builder = ZooKeeperProtos.Table.newBuilder();
      int magicLen = ProtobufUtil.lengthOfPBMagic();
      ZooKeeperProtos.Table t = builder.mergeFrom(data, magicLen, data.length - magicLen).build();
      return t.getState();
    } catch (InvalidProtocolBufferException e) {
      KeeperException ke = new KeeperException.DataInconsistencyException();
      ke.initCause(e);
      throw ke;
    } catch (DeserializationException e) {
      throw ZKUtil.convert(e);
    }
  }

  /**
   * Sets the specified table as DISABLED in zookeeper.  Fails silently if the
   * table is already disabled in zookeeper.  Sets no watches.
   * @param tableName
   * @throws KeeperException unexpected zookeeper exception
   */
  public void setDisabledTable(String tableName)
  throws KeeperException {
    synchronized (this.cache) {
      if (!isDisablingOrDisabledTable(tableName)) {
        LOG.warn("Moving table " + tableName + " state to disabled but was " +
          "not first in disabling state: " + this.cache.get(tableName));
      }
      setTableState(tableName, ZooKeeperProtos.Table.State.DISABLED);
    }
  }

  /**
   * Sets the specified table as DISABLING in zookeeper.  Fails silently if the
   * table is already disabled in zookeeper.  Sets no watches.
   * @param tableName
   * @throws KeeperException unexpected zookeeper exception
   */
  public void setDisablingTable(final String tableName)
  throws KeeperException {
    synchronized (this.cache) {
      if (!isEnabledOrDisablingTable(tableName)) {
        LOG.warn("Moving table " + tableName + " state to disabling but was " +
          "not first in enabled state: " + this.cache.get(tableName));
      }
      setTableState(tableName, ZooKeeperProtos.Table.State.DISABLING);
    }
  }

  /**
   * Sets the specified table as ENABLING in zookeeper.  Fails silently if the
   * table is already disabled in zookeeper.  Sets no watches.
   * @param tableName
   * @throws KeeperException unexpected zookeeper exception
   */
  public void setEnablingTable(final String tableName)
  throws KeeperException {
    synchronized (this.cache) {
      if (!isDisabledOrEnablingTable(tableName)) {
        LOG.warn("Moving table " + tableName + " state to enabling but was " +
          "not first in disabled state: " + this.cache.get(tableName));
      }
      setTableState(tableName, ZooKeeperProtos.Table.State.ENABLING);
    }
  }

  /**
   * Sets the specified table as ENABLING in zookeeper atomically
   * If the table is already in ENABLING state, no operation is performed
   * @param tableName
   * @return if the operation succeeds or not
   * @throws KeeperException unexpected zookeeper exception
   */
  public boolean checkAndSetEnablingTable(final String tableName)
    throws KeeperException {
    synchronized (this.cache) {
      if (isEnablingTable(tableName)) {
        return false;
      }
      setTableState(tableName, ZooKeeperProtos.Table.State.ENABLING);
      return true;
    }
  }

  /**
   * Sets the specified table as ENABLING in zookeeper atomically
   * If the table isn't in DISABLED state, no operation is performed
   * @param tableName
   * @return if the operation succeeds or not
   * @throws KeeperException unexpected zookeeper exception
   */
  public boolean checkDisabledAndSetEnablingTable(final String tableName)
    throws KeeperException {
    synchronized (this.cache) {
      if (!isDisabledTable(tableName)) {
        return false;
      }
      setTableState(tableName, ZooKeeperProtos.Table.State.ENABLING);
      return true;
    }
  }

  /**
   * Sets the specified table as DISABLING in zookeeper atomically
   * If the table isn't in ENABLED state, no operation is performed
   * @param tableName
   * @return if the operation succeeds or not
   * @throws KeeperException unexpected zookeeper exception
   */
  public boolean checkEnabledAndSetDisablingTable(final String tableName)
    throws KeeperException {
    synchronized (this.cache) {
      if (this.cache.get(tableName) != null && !isEnabledTable(tableName)) {
        return false;
      }
      setTableState(tableName, ZooKeeperProtos.Table.State.DISABLING);
      return true;
    }
  }

  private void setTableState(final String tableName, final ZooKeeperProtos.Table.State state)
  throws KeeperException {
    String znode = ZKUtil.joinZNode(this.watcher.tableZNode, tableName);
    if (ZKUtil.checkExists(this.watcher, znode) == -1) {
      ZKUtil.createAndFailSilent(this.watcher, znode);
    }
    synchronized (this.cache) {
      ZooKeeperProtos.Table.Builder builder = ZooKeeperProtos.Table.newBuilder();
      builder.setState(state);
      byte [] data = ProtobufUtil.prependPBMagic(builder.build().toByteArray());
      ZKUtil.setData(this.watcher, znode, data);
      this.cache.put(tableName, state);
    }
  }

  public boolean isDisabledTable(final String tableName) {
    return isTableState(tableName, ZooKeeperProtos.Table.State.DISABLED);
  }

  /**
   * Go to zookeeper and see if state of table is {@link TableState#DISABLED}.
   * This method does not use cache as {@link #isDisabledTable(String)} does.
   * This method is for clients other than {@link AssignmentManager}
   * @param zkw
   * @param tableName
   * @return True if table is enabled.
   * @throws KeeperException
   */
  public static boolean isDisabledTable(final ZooKeeperWatcher zkw,
      final String tableName)
  throws KeeperException {
    ZooKeeperProtos.Table.State state = getTableState(zkw, tableName);
    return isTableState(ZooKeeperProtos.Table.State.DISABLED, state);
  }

  public boolean isDisablingTable(final String tableName) {
    return isTableState(tableName, ZooKeeperProtos.Table.State.DISABLING);
  }

  public boolean isEnablingTable(final String tableName) {
    return isTableState(tableName, ZooKeeperProtos.Table.State.ENABLING);
  }

  public boolean isEnabledTable(String tableName) {
    return isTableState(tableName, ZooKeeperProtos.Table.State.ENABLED);
  }

  /**
   * Go to zookeeper and see if state of table is {@link TableState#ENABLED}.
   * This method does not use cache as {@link #isEnabledTable(String)} does.
   * This method is for clients other than {@link AssignmentManager}
   * @param zkw
   * @param tableName
   * @return True if table is enabled.
   * @throws KeeperException
   */
  public static boolean isEnabledTable(final ZooKeeperWatcher zkw,
      final String tableName)
  throws KeeperException {
    return getTableState(zkw, tableName) == ZooKeeperProtos.Table.State.ENABLED;
  }

  public boolean isDisablingOrDisabledTable(final String tableName) {
    synchronized (this.cache) {
      return isDisablingTable(tableName) || isDisabledTable(tableName);
    }
  }

  /**
   * Go to zookeeper and see if state of table is {@link TableState#DISABLING}
   * of {@link TableState#DISABLED}.
   * This method does not use cache as {@link #isEnabledTable(String)} does.
   * This method is for clients other than {@link AssignmentManager}.
   * @param zkw
   * @param tableName
   * @return True if table is enabled.
   * @throws KeeperException
   */
  public static boolean isDisablingOrDisabledTable(final ZooKeeperWatcher zkw,
      final String tableName)
  throws KeeperException {
    ZooKeeperProtos.Table.State state = getTableState(zkw, tableName);
    return isTableState(ZooKeeperProtos.Table.State.DISABLING, state) ||
      isTableState(ZooKeeperProtos.Table.State.DISABLED, state);
  }

  public boolean isEnabledOrDisablingTable(final String tableName) {
    synchronized (this.cache) {
      return isEnabledTable(tableName) || isDisablingTable(tableName);
    }
  }

  public boolean isDisabledOrEnablingTable(final String tableName) {
    synchronized (this.cache) {
      return isDisabledTable(tableName) || isEnablingTable(tableName);
    }
  }

  private boolean isTableState(final String tableName, final ZooKeeperProtos.Table.State state) {
    synchronized (this.cache) {
      ZooKeeperProtos.Table.State currentState = this.cache.get(tableName);
      return isTableState(currentState, state);
    }
  }

  private static boolean isTableState(final ZooKeeperProtos.Table.State expectedState,
      final ZooKeeperProtos.Table.State currentState) {
    return currentState != null && currentState.equals(expectedState);
  }

  /**
   * Deletes the table in zookeeper.  Fails silently if the
   * table is not currently disabled in zookeeper.  Sets no watches.
   * @param tableName
   * @throws KeeperException unexpected zookeeper exception
   */
  public void setDeletedTable(final String tableName)
  throws KeeperException {
    synchronized (this.cache) {
      if (this.cache.remove(tableName) == null) {
        LOG.warn("Moving table " + tableName + " state to deleted but was " +
          "already deleted");
      }
      ZKUtil.deleteNodeFailSilent(this.watcher,
        ZKUtil.joinZNode(this.watcher.tableZNode, tableName));
    }
  }
  
  /**
   * Sets the ENABLED state in the cache and deletes the zookeeper node. Fails
   * silently if the node is not in enabled in zookeeper
   * 
   * @param tableName
   * @throws KeeperException
   */
  public void setEnabledTable(final String tableName) throws KeeperException {
    setTableState(tableName, ZooKeeperProtos.Table.State.ENABLED);
  }

  /**
   * check if table is present .
   * 
   * @param tableName
   * @return true if the table is present
   */
  public boolean isTablePresent(final String tableName) {
    synchronized (this.cache) {
      ZooKeeperProtos.Table.State state = this.cache.get(tableName);
      return !(state == null);
    }
  }
  
  /**
   * Gets a list of all the tables set as disabled in zookeeper.
   * @return Set of disabled tables, empty Set if none
   */
  public Set<String> getDisabledTables() {
    Set<String> disabledTables = new HashSet<String>();
    synchronized (this.cache) {
      Set<String> tables = this.cache.keySet();
      for (String table: tables) {
        if (isDisabledTable(table)) disabledTables.add(table);
      }
    }
    return disabledTables;
  }

  /**
   * Gets a list of all the tables set as disabled in zookeeper.
   * @return Set of disabled tables, empty Set if none
   * @throws KeeperException 
   */
  public static Set<String> getDisabledTables(ZooKeeperWatcher zkw)
  throws KeeperException {
    Set<String> disabledTables = new HashSet<String>();
    List<String> children =
      ZKUtil.listChildrenNoWatch(zkw, zkw.tableZNode);
    for (String child: children) {
      ZooKeeperProtos.Table.State state = getTableState(zkw, child);
      if (state == ZooKeeperProtos.Table.State.DISABLED) disabledTables.add(child);
    }
    return disabledTables;
  }

  /**
   * Gets a list of all the tables set as disabled in zookeeper.
   * @return Set of disabled tables, empty Set if none
   * @throws KeeperException 
   */
  public static Set<String> getDisabledOrDisablingTables(ZooKeeperWatcher zkw)
  throws KeeperException {
    Set<String> disabledTables = new HashSet<String>();
    List<String> children =
      ZKUtil.listChildrenNoWatch(zkw, zkw.tableZNode);
    for (String child: children) {
      ZooKeeperProtos.Table.State state = getTableState(zkw, child);
      if (state == ZooKeeperProtos.Table.State.DISABLED ||
          state == ZooKeeperProtos.Table.State.DISABLING)
        disabledTables.add(child);
    }
    return disabledTables;
  }
}