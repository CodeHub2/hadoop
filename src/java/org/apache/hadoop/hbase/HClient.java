/**
 * Copyright 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;

/**
 * HClient manages a connection to a single HRegionServer.
 */
public class HClient implements HConstants {
  final Log LOG = LogFactory.getLog(this.getClass().getName());
  
  private static final Text[] META_COLUMNS = {
    COLUMN_FAMILY
  };
  
  private static final Text[] REGIONINFO = {
    COL_REGIONINFO
  };
  
  private static final Text EMPTY_START_ROW = new Text();
  
  long pause;
  int numRetries;
  private HMasterInterface master;
  private final Configuration conf;
  
  /*
   * Data structure that holds current location for a region and its info.
   */
  static class RegionLocation {
    public HRegionInfo regionInfo;
    public HServerAddress serverAddress;

    RegionLocation(HRegionInfo regionInfo, HServerAddress serverAddress) {
      this.regionInfo = regionInfo;
      this.serverAddress = serverAddress;
    }
    
    @Override
    public String toString() {
      return "address: " + this.serverAddress.toString() + ", regioninfo: " +
        this.regionInfo;
    }
  }
  
  // Map tableName -> (Map startRow -> (HRegionInfo, HServerAddress)
  private TreeMap<Text, SortedMap<Text, RegionLocation>> tablesToServers;
  
  // For the "current" table: Map startRow -> (HRegionInfo, HServerAddress)
  private SortedMap<Text, RegionLocation> tableServers;
  
  // Known region HServerAddress.toString() -> HRegionInterface 
  private TreeMap<String, HRegionInterface> servers;
  
  // For row mutation operations

  Text currentRegion;
  HRegionInterface currentServer;
  Random rand;
  long clientid;

  /** Creates a new HClient */
  public HClient(Configuration conf) {
    this.conf = conf;

    this.pause = conf.getLong("hbase.client.pause", 30 * 1000);
    this.numRetries = conf.getInt("hbase.client.retries.number", 5);
    
    this.master = null;
    this.tablesToServers = new TreeMap<Text, SortedMap<Text, RegionLocation>>();
    this.tableServers = null;
    this.servers = new TreeMap<String, HRegionInterface>();
    
    // For row mutation operations

    this.currentRegion = null;
    this.currentServer = null;
    this.rand = new Random();
  }
  
  private void handleRemoteException(RemoteException e) throws IOException {
    String msg = e.getMessage();
    if(e.getClassName().equals("org.apache.hadoop.hbase.InvalidColumnNameException")) {
      throw new InvalidColumnNameException(msg);
      
    } else if(e.getClassName().equals("org.apache.hadoop.hbase.LockException")) {
      throw new LockException(msg);
      
    } else if(e.getClassName().equals("org.apache.hadoop.hbase.MasterNotRunningException")) {
      throw new MasterNotRunningException(msg);
      
    } else if(e.getClassName().equals("org.apache.hadoop.hbase.NoServerForRegionException")) {
      throw new NoServerForRegionException(msg);
      
    } else if(e.getClassName().equals("org.apache.hadoop.hbase.NotServingRegionException")) {
      throw new NotServingRegionException(msg);
      
    } else if(e.getClassName().equals("org.apache.hadoop.hbase.TableNotDisabledException")) {
      throw new TableNotDisabledException(msg);
      
    } else {
      throw e;
    }
  }
  
  /* Find the address of the master and connect to it
   */
  private void checkMaster() throws MasterNotRunningException {
    if (this.master != null) {
      return;
    }
    
    for(int tries = 0; this.master == null && tries < numRetries; tries++) {
      HServerAddress masterLocation =
        new HServerAddress(this.conf.get(MASTER_ADDRESS,
          DEFAULT_MASTER_ADDRESS));
      
      try {
        HMasterInterface tryMaster =
          (HMasterInterface)RPC.getProxy(HMasterInterface.class, 
            HMasterInterface.versionID, masterLocation.getInetSocketAddress(),
            this.conf);
        if(tryMaster.isMasterRunning()) {
          this.master = tryMaster;
          break;
        }
      } catch(IOException e) {
        if(tries == numRetries - 1) {
          // This was our last chance - don't bother sleeping
          break;
        }
        LOG.info("Attempt " + tries + " of " + this.numRetries +
          " failed with <" + e + ">. Retrying after sleep of " + this.pause);
      }
      
      // We either cannot connect to master or it is not running. Sleep & retry
      try {
        Thread.sleep(this.pause);
      } catch(InterruptedException e) {
        // continue
      }
    }
    
    if(this.master == null) {
      throw new MasterNotRunningException();
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Administrative methods
  //////////////////////////////////////////////////////////////////////////////

  /**
   * @return - true if the master server is running
   */
  public boolean isMasterRunning() {
    if(this.master == null) {
      try {
        checkMaster();
        
      } catch(MasterNotRunningException e) {
        return false;
      }
    }
    return true;
  }
  
  /**
   * Creates a new table
   * 
   * @param desc - table descriptor for table
   * 
   * @throws IllegalArgumentException - if the table name is reserved
   * @throws MasterNotRunningException - if master is not running
   * @throws NoServerForRegionException - if root region is not being served
   * @throws IOException
   */
  public synchronized void createTable(HTableDescriptor desc) throws IOException {
    checkReservedTableName(desc.getName());
    checkMaster();
    try {
      this.master.createTable(desc);
      
    } catch(RemoteException e) {
      handleRemoteException(e);
    }

    // Save the current table
    
    SortedMap<Text, RegionLocation> oldServers = this.tableServers;

    try {
      // Wait for new table to come on-line

      findServersForTable(desc.getName());
      
    } finally {
      if(oldServers != null && oldServers.size() != 0) {
        // Restore old current table if there was one
      
        this.tableServers = oldServers;
      }
    }
  }

  public synchronized void deleteTable(Text tableName) throws IOException {
    checkReservedTableName(tableName);
    checkMaster();
    RegionLocation firstMetaServer = getFirstMetaServerForTable(tableName);

    try {
      this.master.deleteTable(tableName);
    } catch(RemoteException e) {
      handleRemoteException(e);
    }

    // Wait until first region is deleted
    HRegionInterface server =
      getHRegionConnection(firstMetaServer.serverAddress);
    DataInputBuffer inbuf = new DataInputBuffer();
    HStoreKey key = new HStoreKey();
    HRegionInfo info = new HRegionInfo();
    for (int tries = 0; tries < numRetries; tries++) {
      long scannerId = -1L;
      try {
        scannerId = server.openScanner(firstMetaServer.regionInfo.regionName,
            REGIONINFO, tableName);
        LabelledData[] values = server.next(scannerId, key);
        if(values == null || values.length == 0) {
          break;
        }
        boolean found = false;
        for(int j = 0; j < values.length; j++) {
          if(values[j].getLabel().equals(COL_REGIONINFO)) {
            byte[] bytes = new byte[values[j].getData().getSize()];
            System.arraycopy(values[j].getData().get(), 0, bytes, 0,
              bytes.length);
            inbuf.reset(bytes, bytes.length);
            info.readFields(inbuf);
            if(info.tableDesc.getName().equals(tableName)) {
              found = true;
            }
          }
        }
        if(!found) {
          break;
        }
        
      } finally {
        if(scannerId != -1L) {
          try {
            server.close(scannerId);
          } catch(Exception e) {
            LOG.warn(e);
          }
        }
      }

      try {
        Thread.sleep(pause);
      } catch(InterruptedException e) {
        // continue
      }
    }
    LOG.info("table " + tableName + " deleted");
  }

  public synchronized void addColumn(Text tableName, HColumnDescriptor column) throws IOException {
    checkReservedTableName(tableName);
    checkMaster();
    try {
      this.master.addColumn(tableName, column);
      
    } catch(RemoteException e) {
      handleRemoteException(e);
    }
  }

  public synchronized void deleteColumn(Text tableName, Text columnName) throws IOException {
    checkReservedTableName(tableName);
    checkMaster();
    try {
      this.master.deleteColumn(tableName, columnName);
      
    } catch(RemoteException e) {
      handleRemoteException(e);
    }
  }
  
  public synchronized void enableTable(Text tableName) throws IOException {
    checkReservedTableName(tableName);
    checkMaster();
    RegionLocation firstMetaServer = getFirstMetaServerForTable(tableName);
    
    try {
      this.master.enableTable(tableName);
      
    } catch(RemoteException e) {
      handleRemoteException(e);
    }

    // Wait until first region is enabled
    
    HRegionInterface server = getHRegionConnection(firstMetaServer.serverAddress);

    DataInputBuffer inbuf = new DataInputBuffer();
    HStoreKey key = new HStoreKey();
    HRegionInfo info = new HRegionInfo();
    for(int tries = 0; tries < numRetries; tries++) {
      int valuesfound = 0;
      long scannerId = -1L;
      try {
        scannerId = server.openScanner(firstMetaServer.regionInfo.regionName,
            REGIONINFO, tableName);
        LabelledData[] values = server.next(scannerId, key);
        if(values == null || values.length == 0) {
          if(valuesfound == 0) {
            throw new NoSuchElementException("table " + tableName + " not found");
          }
        }
        valuesfound += 1;
        boolean isenabled = false;
        for(int j = 0; j < values.length; j++) {
          if(values[j].getLabel().equals(COL_REGIONINFO)) {
            byte[] bytes = new byte[values[j].getData().getSize()];
            System.arraycopy(values[j].getData().get(), 0, bytes, 0, bytes.length);
            inbuf.reset(bytes, bytes.length);
            info.readFields(inbuf);
            isenabled = !info.offLine;
          }
        }
        if(isenabled) {
          break;
        }
        
      } finally {
        if(scannerId != -1L) {
          try {
            server.close(scannerId);
            
          } catch(Exception e) {
            LOG.warn(e);
          }
        }
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug("Sleep. Waiting for first region to be enabled from " + tableName);
      }
      try {
        Thread.sleep(pause);
        
      } catch(InterruptedException e) {
        // continue
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug("Wake. Waiting for first region to be enabled from " + tableName);
      }
    }
    LOG.info("Enabled table " + tableName);
  }

  public synchronized void disableTable(Text tableName) throws IOException {
    checkReservedTableName(tableName);
    checkMaster();
    RegionLocation firstMetaServer = getFirstMetaServerForTable(tableName);

    try {
      this.master.disableTable(tableName);
      
    } catch(RemoteException e) {
      handleRemoteException(e);
    }

    // Wait until first region is disabled
    
    HRegionInterface server = getHRegionConnection(firstMetaServer.serverAddress);

    DataInputBuffer inbuf = new DataInputBuffer();
    HStoreKey key = new HStoreKey();
    HRegionInfo info = new HRegionInfo();
    for(int tries = 0; tries < numRetries; tries++) {
      int valuesfound = 0;
      long scannerId = -1L;
      try {
        scannerId = server.openScanner(firstMetaServer.regionInfo.regionName,
            REGIONINFO, tableName);
        LabelledData[] values = server.next(scannerId, key);
        if(values == null || values.length == 0) {
          if(valuesfound == 0) {
            throw new NoSuchElementException("table " + tableName + " not found");
          }
        }
        valuesfound += 1;
        boolean disabled = false;
        for(int j = 0; j < values.length; j++) {
          if(values[j].getLabel().equals(COL_REGIONINFO)) {
            byte[] bytes = new byte[values[j].getData().getSize()];
            System.arraycopy(values[j].getData().get(), 0, bytes, 0, bytes.length);
            inbuf.reset(bytes, bytes.length);
            info.readFields(inbuf);
            disabled = info.offLine;
          }
        }
        if(disabled) {
          break;
        }
        
      } finally {
        if(scannerId != -1L) {
          try {
            server.close(scannerId);
            
          } catch(Exception e) {
            LOG.warn(e);
          }
        }
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug("Sleep. Waiting for first region to be disabled from " + tableName);
      }
      try {
        Thread.sleep(pause);
      } catch(InterruptedException e) {
        // continue
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug("Wake. Waiting for first region to be disabled from " + tableName);
      }
    }
    LOG.info("Disabled table " + tableName);
  }
  
  public synchronized void shutdown() throws IOException {
    checkMaster();
    this.master.shutdown();
  }

  /*
   * Verifies that the specified table name is not a reserved name
   * @param tableName - the table name to be checked
   * @throws IllegalArgumentException - if the table name is reserved
   */
  private void checkReservedTableName(Text tableName) {
    if(tableName.equals(ROOT_TABLE_NAME)
        || tableName.equals(META_TABLE_NAME)) {
      
      throw new IllegalArgumentException(tableName + " is a reserved table name");
    }
  }
  
  private RegionLocation getFirstMetaServerForTable(Text tableName) throws IOException {
    SortedMap<Text, RegionLocation> metaservers = findMetaServersForTable(tableName);
    return metaservers.get(metaservers.firstKey());
  }
  
  //////////////////////////////////////////////////////////////////////////////
  // Client API
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Loads information so that a table can be manipulated.
   * 
   * @param tableName - the table to be located
   * @throws IOException - if the table can not be located after retrying
   */
  public synchronized void openTable(Text tableName) throws IOException {
    if(tableName == null || tableName.getLength() == 0) {
      throw new IllegalArgumentException("table name cannot be null or zero length");
    }
    this.tableServers = tablesToServers.get(tableName);
    if (this.tableServers == null ) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("No servers for " + tableName + ". Doing a find...");
      }
      // We don't know where the table is.
      // Load the information from meta.
      this.tableServers = findServersForTable(tableName);
    }
  }

  /*
   * Locates a table by searching the META region
   * 
   * @param tableName - name of table to find servers for
   * @return - map of first row to table info for all regions in the table
   * @throws IOException
   */
  private SortedMap<Text, RegionLocation> findServersForTable(Text tableName)
      throws IOException {
    SortedMap<Text, RegionLocation> servers = null;
    if(tableName.equals(ROOT_TABLE_NAME)) {
      servers = locateRootRegion();
    } else if(tableName.equals(META_TABLE_NAME)) {
      servers = loadMetaFromRoot();
    } else {
      servers = new TreeMap<Text, RegionLocation>();
      for(RegionLocation t: findMetaServersForTable(tableName).values()) {
        servers.putAll(scanOneMetaRegion(t, tableName));
      }
      this.tablesToServers.put(tableName, servers);
    }
    if (LOG.isDebugEnabled()) {
      for (Map.Entry<Text, RegionLocation> e: servers.entrySet()) {
        LOG.debug("Server " + e.getKey() + " is serving: " + e.getValue());
      }
    }
    return servers;
  }

  /*
   * Finds the meta servers that contain information about the specified table
   * @param tableName - the name of the table to get information about
   * @return - returns a SortedMap of the meta servers
   * @throws IOException
   */
  private SortedMap<Text, RegionLocation> findMetaServersForTable(final Text tableName)
  throws IOException {
    SortedMap<Text, RegionLocation> metaServers =
      this.tablesToServers.get(META_TABLE_NAME);
    if(metaServers == null) {                 // Don't know where the meta is
      metaServers = loadMetaFromRoot();
    }
    Text firstMetaRegion = (metaServers.containsKey(tableName)) ?
      tableName : metaServers.headMap(tableName).lastKey();
    return metaServers.tailMap(firstMetaRegion);
  }

  /*
   * Load the meta table from the root table.
   * 
   * @return map of first row to TableInfo for all meta regions
   * @throws IOException
   */
  private TreeMap<Text, RegionLocation> loadMetaFromRoot() throws IOException {
    SortedMap<Text, RegionLocation> rootRegion =
      this.tablesToServers.get(ROOT_TABLE_NAME);
    if(rootRegion == null) {
      rootRegion = locateRootRegion();
    }
    return scanRoot(rootRegion.get(rootRegion.firstKey()));
  }
  
  /*
   * Repeatedly try to find the root region by asking the master for where it is
   * @return TreeMap<Text, TableInfo> for root regin if found
   * @throws NoServerForRegionException - if the root region can not be located after retrying
   * @throws IOException 
   */
  private TreeMap<Text, RegionLocation> locateRootRegion() throws IOException {
    checkMaster();
    
    HServerAddress rootRegionLocation = null;
    for(int tries = 0; tries < numRetries; tries++) {
      int localTimeouts = 0;
      while(rootRegionLocation == null && localTimeouts < numRetries) {
        rootRegionLocation = master.findRootRegion();
        if(rootRegionLocation == null) {
          try {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Sleeping. Waiting for root region.");
            }
            Thread.sleep(this.pause);
            if (LOG.isDebugEnabled()) {
              LOG.debug("Wake. Retry finding root region.");
            }
          } catch(InterruptedException iex) {
            // continue
          }
          localTimeouts++;
        }
      }
      
      if(rootRegionLocation == null) {
        throw new NoServerForRegionException(
            "Timed out trying to locate root region");
      }
      
      HRegionInterface rootRegion = getHRegionConnection(rootRegionLocation);

      try {
        rootRegion.getRegionInfo(HGlobals.rootRegionInfo.regionName);
        break;
      } catch(NotServingRegionException e) {
        if(tries == numRetries - 1) {
          // Don't bother sleeping. We've run out of retries.
          break;
        }
        
        // Sleep and retry finding root region.
        try {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Root region location changed. Sleeping.");
          }
          Thread.sleep(this.pause);
          if (LOG.isDebugEnabled()) {
            LOG.debug("Wake. Retry finding root region.");
          }
        } catch(InterruptedException iex) {
          // continue
        }
      }
      rootRegionLocation = null;
    }
    
    if (rootRegionLocation == null) {
      throw new NoServerForRegionException(
        "unable to locate root region server");
    }
    
    TreeMap<Text, RegionLocation> rootServer = new TreeMap<Text, RegionLocation>();
    rootServer.put(EMPTY_START_ROW,
        new RegionLocation(HGlobals.rootRegionInfo, rootRegionLocation));
    
    this.tablesToServers.put(ROOT_TABLE_NAME, rootServer);
    return rootServer;
  }

  /* 
   * Scans the root region to find all the meta regions
   * @return - TreeMap of meta region servers
   * @throws IOException
   */
  private TreeMap<Text, RegionLocation> scanRoot(RegionLocation rootRegion)
      throws IOException {
    TreeMap<Text, RegionLocation> metaservers =
      scanOneMetaRegion(rootRegion, META_TABLE_NAME);
    this.tablesToServers.put(META_TABLE_NAME, metaservers);
    return metaservers;
  }

  /*
   * Scans a single meta region
   * @param t the meta region we're going to scan
   * @param tableName the name of the table we're looking for
   * @return returns a map of startingRow to TableInfo
   * @throws RegionNotFoundException - if table does not exist
   * @throws IllegalStateException - if table is offline
   * @throws NoServerForRegionException - if table can not be found after retrying
   * @throws IOException 
   */
  private TreeMap<Text, RegionLocation> scanOneMetaRegion(final RegionLocation t,
    final Text tableName)
  throws IOException {
    HRegionInterface server = getHRegionConnection(t.serverAddress);
    TreeMap<Text, RegionLocation> servers = new TreeMap<Text, RegionLocation>();
    for(int tries = 0; servers.size() == 0 && tries < this.numRetries;
        tries++) {
  
      long scannerId = -1L;
      try {
        scannerId =
          server.openScanner(t.regionInfo.regionName, META_COLUMNS, tableName);

        DataInputBuffer inbuf = new DataInputBuffer();
        while(true) {
          HRegionInfo regionInfo = null;
          String serverAddress = null;
          HStoreKey key = new HStoreKey();
          LabelledData[] values = server.next(scannerId, key);
          if(values.length == 0) {
            if(servers.size() == 0) {
              // If we didn't find any servers then the table does not exist
              throw new RegionNotFoundException("table '" + tableName +
                "' does not exist in " + t);
            }

            // We found at least one server for the table and now we're done.
            if (LOG.isDebugEnabled()) {
              LOG.debug("Found " + servers.size() + " server(s) for " +
                "location: " + t + " for tablename " + tableName);
            }
            break;
          }

          byte[] bytes = null;
          TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
          for(int i = 0; i < values.length; i++) {
            bytes = new byte[values[i].getData().getSize()];
            System.arraycopy(values[i].getData().get(), 0, bytes, 0, bytes.length);
            results.put(values[i].getLabel(), bytes);
          }
          regionInfo = new HRegionInfo();
          bytes = results.get(COL_REGIONINFO);
          inbuf.reset(bytes, bytes.length);
          regionInfo.readFields(inbuf);

          if(!regionInfo.tableDesc.getName().equals(tableName)) {
            // We're done
            if (LOG.isDebugEnabled()) {
              LOG.debug("Found " + tableName);
            }
            break;
          }

          if(regionInfo.offLine) {
            throw new IllegalStateException("table offline: " + tableName);
          }

          bytes = results.get(COL_SERVER);
          if(bytes == null || bytes.length == 0) {
            // We need to rescan because the table we want is unassigned.
            if(LOG.isDebugEnabled()) {
              LOG.debug("no server address for " + regionInfo.toString());
            }
            servers.clear();
            break;
          }
          serverAddress = new String(bytes, UTF8_ENCODING);
          servers.put(regionInfo.startKey, 
              new RegionLocation(regionInfo, new HServerAddress(serverAddress)));
        }
      } finally {
        if(scannerId != -1L) {
          try {
            server.close(scannerId);
          } catch(Exception e) {
            LOG.warn(e);
          }
        }
      }
        
      if(servers.size() == 0 && tries == this.numRetries - 1) {
        throw new NoServerForRegionException("failed to find server for "
            + tableName + " after " + this.numRetries + " retries");
      }

      if (servers.size() <= 0) {
        // The table is not yet being served. Sleep and retry.
        if (LOG.isDebugEnabled()) {
          LOG.debug("Sleeping. Table " + tableName +
            " not currently being served.");
        }
        try {
          Thread.sleep(this.pause);
        } catch (InterruptedException e) {
          // continue
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug("Wake. Retry finding table " + tableName);
        }
      }
    }
    return servers;
  }

  /* 
   * Establishes a connection to the region server at the specified address
   * @param regionServer - the server to connect to
   * @throws IOException
   */
  synchronized HRegionInterface getHRegionConnection(HServerAddress regionServer)
      throws IOException {

      // See if we already have a connection

    HRegionInterface server = this.servers.get(regionServer.toString());
    
    if(server == null) {                                // Get a connection
      
      server = (HRegionInterface)RPC.waitForProxy(HRegionInterface.class, 
          HRegionInterface.versionID, regionServer.getInetSocketAddress(),
          this.conf);
      
      this.servers.put(regionServer.toString(), server);
    }
    return server;
  }

  /**
   * List all the userspace tables.  In other words, scan the META table.
   *
   * If we wanted this to be really fast, we could implement a special
   * catalog table that just contains table names and their descriptors.
   * Right now, it only exists as part of the META table's region info.
   */
  public synchronized HTableDescriptor[] listTables()
      throws IOException {
    TreeSet<HTableDescriptor> uniqueTables = new TreeSet<HTableDescriptor>();
    
    SortedMap<Text, RegionLocation> metaTables =
      this.tablesToServers.get(META_TABLE_NAME);
    
    if(metaTables == null) {
      // Meta is not loaded yet so go do that
      metaTables = loadMetaFromRoot();
    }

    for (RegionLocation t: metaTables.values()) {
      HRegionInterface server = getHRegionConnection(t.serverAddress);
      long scannerId = -1L;
      try {
        scannerId = server.openScanner(t.regionInfo.regionName,
            META_COLUMNS, EMPTY_START_ROW);
        
        HStoreKey key = new HStoreKey();
        DataInputBuffer inbuf = new DataInputBuffer();
        while(true) {
          LabelledData[] values = server.next(scannerId, key);
          if(values.length == 0) {
            break;
          }
          for(int i = 0; i < values.length; i++) {
            if(values[i].getLabel().equals(COL_REGIONINFO)) {
              byte[] bytes = values[i].getData().get();
              inbuf.reset(bytes, bytes.length);
              HRegionInfo info = new HRegionInfo();
              info.readFields(inbuf);

              // Only examine the rows where the startKey is zero length   
              if(info.startKey.getLength() == 0) {
                uniqueTables.add(info.tableDesc);
              }
            }
          }
        }
      } finally {
        if(scannerId != -1L) {
          server.close(scannerId);
        }
      }
    }
    return uniqueTables.toArray(new HTableDescriptor[uniqueTables.size()]);
  }

  /*
   * Find region location hosting passed row using cached info
   * @param row Row to find.
   * @return Location of row.
   */
  synchronized RegionLocation getRegionLocation(Text row) {
    if(row == null || row.getLength() == 0) {
      throw new IllegalArgumentException("row key cannot be null or zero length");
    }
    if(this.tableServers == null) {
      throw new IllegalStateException("Must open table first");
    }
    
    // Only one server will have the row we are looking for
    Text serverKey = (this.tableServers.containsKey(row))? row:
      this.tableServers.headMap(row).lastKey();
    return this.tableServers.get(serverKey);
  }
  
  /*
   * Clear caches of passed region location, reload servers for the passed
   * region's table and then ensure region location can be found.
   * @param info Region location to find.
   * @throws IOException
   */
  synchronized void findRegion(final RegionLocation info) throws IOException {
    // Wipe out everything we know about this table
    if (LOG.isDebugEnabled()) {
      LOG.debug("Wiping out all we know of " + info);
    }
    this.tablesToServers.remove(info.regionInfo.tableDesc.getName());
    this.tableServers.clear();
    
    // Reload information for the whole table
    this.tableServers = findServersForTable(info.regionInfo.tableDesc.getName());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Result of findRegion: " + this.tableServers.toString());
    }
    if (this.tableServers.get(info.regionInfo.startKey) == null) {
      throw new RegionNotFoundException(info.regionInfo.regionName.toString());
    }
  }
  
  /** Get a single value for the specified row and column */
  public byte[] get(Text row, Text column) throws IOException {
    RegionLocation info = null;
    BytesWritable value = null;

    for(int tries = 0; tries < numRetries && info == null; tries++) {
      info = getRegionLocation(row);
      
      try {
        value = getHRegionConnection(info.serverAddress).get(
            info.regionInfo.regionName, row, column);
        
      } catch(NotServingRegionException e) {
        if(tries == numRetries - 1) {
          // No more tries
          throw e;
        }
        findRegion(info);
        info = null;
      }
    }

    if(value != null) {
      byte[] bytes = new byte[value.getSize()];
      System.arraycopy(value.get(), 0, bytes, 0, bytes.length);
      return bytes;
    }
    return null;
  }
 
  /** Get the specified number of versions of the specified row and column */
  public byte[][] get(Text row, Text column, int numVersions) throws IOException {
    RegionLocation info = null;
    BytesWritable[] values = null;

    for(int tries = 0; tries < numRetries && info == null; tries++) {
      info = getRegionLocation(row);
      
      try {
        values = getHRegionConnection(info.serverAddress).get(
            info.regionInfo.regionName, row, column, numVersions);
        
      } catch(NotServingRegionException e) {
        if(tries == numRetries - 1) {
          // No more tries
          throw e;
        }
        findRegion(info);
        info = null;
      }
    }

    if(values != null) {
      ArrayList<byte[]> bytes = new ArrayList<byte[]>();
      for(int i = 0 ; i < values.length; i++) {
        byte[] value = new byte[values[i].getSize()];
        System.arraycopy(values[i].get(), 0, value, 0, value.length);
        bytes.add(value);
      }
      return bytes.toArray(new byte[values.length][]);
    }
    return null;
  }
  
  /** 
   * Get the specified number of versions of the specified row and column with
   * the specified timestamp.
   */
  public byte[][] get(Text row, Text column, long timestamp, int numVersions) throws IOException {
    RegionLocation info = null;
    BytesWritable[] values = null;

    for(int tries = 0; tries < numRetries && info == null; tries++) {
      info = getRegionLocation(row);
      
      try {
        values = getHRegionConnection(info.serverAddress).get(
            info.regionInfo.regionName, row, column, timestamp, numVersions);
    
      } catch(NotServingRegionException e) {
        if(tries == numRetries - 1) {
          // No more tries
          throw e;
        }
        findRegion(info);
        info = null;
      }
    }

    if(values != null) {
      ArrayList<byte[]> bytes = new ArrayList<byte[]>();
      for(int i = 0 ; i < values.length; i++) {
        byte[] value = new byte[values[i].getSize()];
        System.arraycopy(values[i].get(), 0, value, 0, value.length);
        bytes.add(value);
      }
      return bytes.toArray(new byte[values.length][]);
    }
    return null;
  }
    
  /** Get all the data for the specified row */
  public LabelledData[] getRow(Text row) throws IOException {
    RegionLocation info = null;
    LabelledData[] value = null;
    
    for(int tries = 0; tries < numRetries && info == null; tries++) {
      info = getRegionLocation(row);
      
      try {
        value = getHRegionConnection(info.serverAddress).getRow(
            info.regionInfo.regionName, row);
        
      } catch(NotServingRegionException e) {
        if(tries == numRetries - 1) {
          // No more tries
          throw e;
        }
        findRegion(info);
        info = null;
      }
    }
    
    return value;
  }

  /** 
   * Get a scanner on the current table starting at the specified row.
   * Return the specified columns.
   */
  public synchronized HScannerInterface obtainScanner(Text[] columns, Text startRow) throws IOException {
    if(this.tableServers == null) {
      throw new IllegalStateException("Must open table first");
    }
    return new ClientScanner(columns, startRow);
  }
  
  /*
   * @return General HClient RetryPolicy instance.
   */
  RetryPolicy getRetryPolicy() {
    Map<Class <? extends Exception>, RetryPolicy> exceptionToPolicyMap =
      new HashMap<Class <? extends Exception>, RetryPolicy>();
    // Pass numRetries - 1 because it does less-than-equal internally rather
    // than the less-than we do elsewhere where we use numRetries.
    RetryPolicy rp =
      RetryPolicies.retryUpToMaximumCountWithProportionalSleep(numRetries,
        this.pause, TimeUnit.MILLISECONDS);
    exceptionToPolicyMap.put(NotServingRegionException.class, rp);
    exceptionToPolicyMap.put(WrongRegionException.class, rp);
    exceptionToPolicyMap.put(RegionNotFoundException.class, rp);
    return RetryPolicies.retryByRemoteException(RetryPolicies.TRY_ONCE_THEN_FAIL,
      exceptionToPolicyMap);
    
  }
  
  /*
   * Interface for {@link #startUpate()} used by the
   * {@link org.apache.hadoop.io.retry} mechanism. 
   */
  private interface StartUpdateInterface {
    /**
     * @return row lockid for the update
     * @throws IOException
     */
    long startUpdate() throws IOException;
  }

  /* Start an atomic row insertion or update
   * @param row Name of row to start update against.
   * @return Row lockid.
   */
  public long startUpdate(final Text row) throws IOException {
    // Implemention of the StartUpdate interface.
    StartUpdateInterface implementation = new StartUpdateInterface() {
      private RegionLocation info = null;
      private int attempts = 0;
      
      /*
       * Wrapped method.  Proxy wrapper is configured to judge whether
       * exception merits retry.
       * @return lockid
       * @throws IOException
       */
      public long startUpdate() throws IOException {
        this.attempts++;
        if (this.info != null) {
          LOG.info("Retry of startUpdate.  Attempt " + this.attempts +
            " for row " + row);
          // If a retry. Something wrong w/ region we have. Refind.
          try {
            findRegion(info);
          } catch (RegionNotFoundException e) {
            // continue.  If no longer exists, perhaps we just came through
            // a split and region is now gone. Below getRegionLocation should
            // recalibrate client.
          }
        }
        this.info = getRegionLocation(row);
        currentServer = getHRegionConnection(info.serverAddress);
        currentRegion = info.regionInfo.regionName;
        clientid = rand.nextLong();
        return currentServer.startUpdate(currentRegion, clientid, row);
      }
    };
    
    // Get retry proxy wrapper around 'implementation'.
    StartUpdateInterface retryProxy = (StartUpdateInterface)RetryProxy.
      create(StartUpdateInterface.class, implementation, getRetryPolicy());
    // Run retry.
    return retryProxy.startUpdate();
  }
  
  /** Change a value for the specified column */
  public void put(long lockid, Text column, byte val[]) throws IOException {
    try {
      this.currentServer.put(this.currentRegion, this.clientid, lockid, column,
          new BytesWritable(val));
    } catch(IOException e) {
      try {
        this.currentServer.abort(this.currentRegion, this.clientid, lockid);
      } catch(IOException e2) {
        LOG.warn(e2);
      }
      this.currentServer = null;
      this.currentRegion = null;
      throw e;
    }
  }
  
  /** Delete the value for a column */
  public void delete(long lockid, Text column) throws IOException {
    try {
      this.currentServer.delete(this.currentRegion, this.clientid, lockid,
        column);
    } catch(IOException e) {
      try {
        this.currentServer.abort(this.currentRegion, this.clientid, lockid);
      } catch(IOException e2) {
        LOG.warn(e2);
      }
      this.currentServer = null;
      this.currentRegion = null;
      throw e;
    }
  }
  
  /** Abort a row mutation */
  public void abort(long lockid) throws IOException {
    try {
      this.currentServer.abort(this.currentRegion, this.clientid, lockid);
    } catch(IOException e) {
      this.currentServer = null;
      this.currentRegion = null;
      throw e;
    }
  }
  
  /** Finalize a row mutation */
  public void commit(long lockid) throws IOException {
    try {
      this.currentServer.commit(this.currentRegion, this.clientid, lockid);
    } finally {
      this.currentServer = null;
      this.currentRegion = null;
    }
  }
  
  /**
   * Implements the scanner interface for the HBase client.
   * If there are multiple regions in a table, this scanner will iterate
   * through them all.
   */
  private class ClientScanner implements HScannerInterface {
    private Text[] columns;
    private Text startRow;
    private boolean closed;
    private RegionLocation[] regions;
    private int currentRegion;
    private HRegionInterface server;
    private long scannerId;
    
    private void loadRegions() {
      Text firstServer = null;
      if(this.startRow == null || this.startRow.getLength() == 0) {
        firstServer = tableServers.firstKey();

      } else if(tableServers.containsKey(startRow)) {
        firstServer = startRow;

      } else {
        firstServer = tableServers.headMap(startRow).lastKey();
      }
      Collection<RegionLocation> info = tableServers.tailMap(firstServer).values();
      this.regions = info.toArray(new RegionLocation[info.size()]);
    }
    
    public ClientScanner(Text[] columns, Text startRow) throws IOException {
      this.columns = columns;
      this.startRow = startRow;
      this.closed = false;
      
      loadRegions();
      this.currentRegion = -1;
      this.server = null;
      this.scannerId = -1L;
      nextScanner();
    }
    
    /*
     * Gets a scanner for the next region.
     * Returns false if there are no more scanners.
     */
    private boolean nextScanner() throws IOException {
      if(this.scannerId != -1L) {
        this.server.close(this.scannerId);
        this.scannerId = -1L;
      }
      this.currentRegion += 1;
      if(this.currentRegion == this.regions.length) {
        close();
        return false;
      }
      try {
        this.server = getHRegionConnection(this.regions[currentRegion].serverAddress);
        
        for(int tries = 0; tries < numRetries; tries++) {
          RegionLocation info = this.regions[currentRegion];
          
          try {
            this.scannerId = this.server.openScanner(info.regionInfo.regionName,
                this.columns, currentRegion == 0 ? this.startRow : EMPTY_START_ROW);
            
            break;
        
          } catch(NotServingRegionException e) {
            if(tries == numRetries - 1) {
              // No more tries
              throw e;
            }
            findRegion(info);
            loadRegions();
          }
        }

      } catch(IOException e) {
        close();
        throw e;
      }
      return true;
    }
    
    /* (non-Javadoc)
     * @see org.apache.hadoop.hbase.HScannerInterface#next(org.apache.hadoop.hbase.HStoreKey, java.util.TreeMap)
     */
    public boolean next(HStoreKey key, TreeMap<Text, byte[]> results) throws IOException {
      if(this.closed) {
        return false;
      }
      LabelledData[] values = null;
      do {
        values = this.server.next(this.scannerId, key);
      } while(values.length == 0 && nextScanner());

      for(int i = 0; i < values.length; i++) {
        byte[] bytes = new byte[values[i].getData().getSize()];
        System.arraycopy(values[i].getData().get(), 0, bytes, 0, bytes.length);
        results.put(values[i].getLabel(), bytes);
      }
      return values.length != 0;
    }

    /* (non-Javadoc)
     * @see org.apache.hadoop.hbase.HScannerInterface#close()
     */
    public void close() throws IOException {
      if(this.scannerId != -1L) {
        this.server.close(this.scannerId);
        this.scannerId = -1L;
      }
      this.server = null;
      this.closed = true;
    }
  }
  
  private void printUsage() {
    printUsage(null);
  }
  
  private void printUsage(final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.println("Usage: java " + this.getClass().getName() +
        " [--master=host:port] <command> <args>");
    System.err.println("Options:");
    System.err.println(" master       Specify host and port of HBase " +
        "cluster master. If not present,");
    System.err.println("              address is read from configuration.");
    System.err.println("Commands:");
    System.err.println(" shutdown     Shutdown the HBase cluster.");
    System.err.println(" createTable  Create named table.");
    System.err.println(" deleteTable  Delete named table.");
    System.err.println(" listTables   List all tables.");
    System.err.println("Example Usage:");
    System.err.println(" % java " + this.getClass().getName() + " shutdown");
    System.err.println(" % java " + this.getClass().getName() +
        " createTable webcrawl contents: anchors: 10");
  }
  
  private void printCreateTableUsage(final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.println("Usage: java " + this.getClass().getName() +
      " [options] createTable <name> <colfamily1> ... <max_versions>");
    System.err.println("Example Usage:");
    System.err.println(" % java " + this.getClass().getName() +
      " createTable testtable column_x column_y column_z 3");
  }
  
  private void printDeleteTableUsage(final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.println("Usage: java " + this.getClass().getName() +
      " [options] deleteTable <name>");
    System.err.println("Example Usage:");
    System.err.println(" % java " + this.getClass().getName() +
      " deleteTable testtable");
  }
  
  public int doCommandLine(final String args[]) {
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).    
    int errCode = -1;
    if (args.length < 1) {
      printUsage();
      return errCode;
    }
    try {
      for (int i = 0; i < args.length; i++) {
        String cmd = args[i];
        if (cmd.equals("-h") || cmd.startsWith("--h")) {
          printUsage();
          errCode = 0;
          break;
        }
        
        final String masterArgKey = "--master=";
        if (cmd.startsWith(masterArgKey)) {
          this.conf.set(MASTER_ADDRESS, cmd.substring(masterArgKey.length()));
          continue;
        }
       
        if (cmd.equals("shutdown")) {
          shutdown();
          errCode = 0;
          break;
        }
        
        if (cmd.equals("listTables")) {
          HTableDescriptor [] tables = listTables();
          for (int ii = 0; ii < tables.length; ii++) {
            System.out.println(tables[ii].getName());
          }
          errCode = 0;
          break;
        }
        
        if (cmd.equals("createTable")) {
          if (i + 2 > args.length) {
            printCreateTableUsage("Error: Supply a table name," +
              " at least one column family, and maximum versions");
            errCode = 1;
            break;
          }
          HTableDescriptor desc = new HTableDescriptor(args[i + 1]);
          boolean addedFamily = false;
          for (int ii = i + 2; ii < (args.length - 1); ii++) {
            desc.addFamily(new HColumnDescriptor(args[ii]));
            addedFamily = true;
          }
          if (!addedFamily) {
            throw new IllegalArgumentException("Must supply at least one " +
              "column family");
          }
          createTable(desc);
          errCode = 0;
          break;
        }
        
        if (cmd.equals("deleteTable")) {
          if (i + 1 > args.length) {
            printDeleteTableUsage("Error: Must supply a table name");
            errCode = 1;
            break;
          }
          deleteTable(new Text(args[i + 1]));
          errCode = 0;
          break;
        }
        
        printUsage();
        break;
      }
    } catch (IOException e) {
      e.printStackTrace();
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
    
    return errCode;
  }
  
  public static void main(final String args[]) {
    Configuration c = new HBaseConfiguration();
    int errCode = (new HClient(c)).doCommandLine(args);
    System.exit(errCode);
  }
}
