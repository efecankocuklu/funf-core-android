/**
 * 
 * Funf: Open Sensing Framework Copyright (C) 2010-2011 Nadav Aharony, Wei Pan, Alex Pentland.
 * Acknowledgments: Alan Gardner Contact: nadav@media.mit.edu
 * 
 * This file is part of Funf.
 * 
 * Funf is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * Funf is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along with Funf. If not,
 * see <http://www.gnu.org/licenses/>.
 * 
 */
package edu.mit.media.funf.pipeline;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.ContentValues;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import edu.mit.media.funf.FunfManager;
import edu.mit.media.funf.Schedule;
import edu.mit.media.funf.config.ConfigUpdater;
import edu.mit.media.funf.config.Configurable;
import edu.mit.media.funf.config.RuntimeTypeAdapterFactory;
import edu.mit.media.funf.json.IJsonObject;
import edu.mit.media.funf.probe.Probe.DataListener;
import edu.mit.media.funf.probe.builtin.ProbeKeys;
import edu.mit.media.funf.storage.DefaultArchive;
import edu.mit.media.funf.storage.FileArchive;
import edu.mit.media.funf.storage.NameValueDatabaseHelper;
import edu.mit.media.funf.storage.RemoteFileArchive;
import edu.mit.media.funf.storage.UploadService;
import edu.mit.media.funf.util.LogUtil;

public class BasicPipeline implements Pipeline, DataListener {

  public static final String 
  ACTION_ARCHIVE = "archive",
  ACTION_UPLOAD = "upload",
  ACTION_UPDATE = "update";
  
  private final int ARCHIVE = 0, UPLOAD = 1, UPDATE = 2, DATA = 3;
  

  @Configurable
  private String name = "default";
  
  @Configurable
  private int version = 1;
  
  @Configurable
  private FileArchive storage = null;
  
  @Configurable
  private RemoteFileArchive backend = null;

  @Configurable
  private ConfigUpdater update = null;

  @Configurable
  private List<JsonElement> data = Collections.unmodifiableList(new ArrayList<JsonElement>());
  
  //Specially named field for collecting schedules
  
  private boolean enabled;
  private Map<String, Schedule> schedules = new HashMap<String, Schedule>(); 
  private FunfManager manager;
  private Gson gson;
  private SQLiteOpenHelper databaseHelper = null;
  private UploadService uploadService = null;
  private Looper looper;
  private Handler handler;
  private Handler.Callback callback = new Handler.Callback() {
    
    @Override
    public boolean handleMessage(Message msg) {
      switch (msg.what) {
        case ARCHIVE:
          if (storage != null) {
            SQLiteDatabase db = databaseHelper.getWritableDatabase();
            // TODO: add check to make sure this is not empty
            File dbFile = new File(db.getPath());
            db.close();
            if (storage.add(dbFile)) {
              dbFile.delete();
            }
            databaseHelper.getWritableDatabase(); // Build new database
          }
          break;
        case UPLOAD:
          if (storage != null && backend != null) {
            if (uploadService != null) {
              uploadService.onDestroy();
            }
            uploadService = new UploadService();
            uploadService.onCreate(manager);
            uploadService.run(storage, backend);
          }
          break;
        case UPDATE:
          if (update != null) {
            update.run(name, manager);
          }
          break;
        case DATA:
          String name = ((JsonObject)msg.obj).get("name").getAsString();
          IJsonObject data = (IJsonObject)((JsonObject)msg.obj).get("value");
          writeData(name, data);
          break;
        default:
          break;
      }
      // TODO Auto-generated method stub
      return false;
    }
  };
  
  protected void writeData(String name, IJsonObject data) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    final double timestamp = data.get(ProbeKeys.BaseProbeKeys.TIMESTAMP).getAsDouble();
    final String value = data.toString();
    if (timestamp == 0L || name == null || value == null) {
        Log.e(LogUtil.TAG, "Unable to save data.  Not all required values specified. " + timestamp + " " + name + " - " + value);
        throw new SQLException("Not all required fields specified.");
    }
    ContentValues cv = new ContentValues();
    cv.put(NameValueDatabaseHelper.COLUMN_NAME, name);
    cv.put(NameValueDatabaseHelper.COLUMN_VALUE, value);
    cv.put(NameValueDatabaseHelper.COLUMN_TIMESTAMP, timestamp);
    db.insertOrThrow(NameValueDatabaseHelper.DATA_TABLE.name, "", cv);
  }


  @Override
  public void onCreate(FunfManager manager) {
    if (storage == null) {
      storage = DefaultArchive.getArchive(manager, name);
    }
    this.manager = manager;
    this.gson = manager.getGsonBuilder().create();
    this.databaseHelper = new NameValueDatabaseHelper(manager, name, version);
    HandlerThread thread = new HandlerThread(getClass().getName());
    thread.start();
    this.looper = thread.getLooper();
    this.handler = new Handler(looper, callback);
    enabled = true;
    for (JsonElement dataRequest : data) {
      manager.requestData(this, dataRequest);
    }
    for (Map.Entry<String, Schedule> schedule : schedules.entrySet()) {
      manager.registerPipelineAction(this, schedule.getKey(), schedule.getValue());
    }
  }

  @Override
  public void onDestroy() {
    for (JsonElement dataRequest : data) {
      manager.unrequestData(this, dataRequest);
    }
    for (Map.Entry<String, Schedule> schedule : schedules.entrySet()) {
      manager.unregisterPipelineAction(this, schedule.getKey());
    }
    if (uploadService != null) {
      uploadService.onDestroy();
    }
    looper.quit();
    enabled = false;
  }

  @Override
  public void onRun(String action, JsonElement config) {
    // Run on handler thread
    if (ACTION_ARCHIVE.equals(action)) {
      handler.obtainMessage(ARCHIVE, config).sendToTarget();
    } else if (ACTION_UPLOAD.equals(action)) {
      handler.obtainMessage(UPLOAD, config).sendToTarget();
    } else if (ACTION_UPDATE.equals(action)) {
      handler.obtainMessage(UPDATE, config).sendToTarget();
    } 
  }
  
  public SQLiteDatabase getDb() {
    return databaseHelper.getReadableDatabase();
  }
  
  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void onDataReceived(IJsonObject probeConfig, IJsonObject data) {
    JsonObject record = new JsonObject();
    record.add("name", probeConfig.get(RuntimeTypeAdapterFactory.TYPE));
    record.add("value", data);
    handler.obtainMessage(DATA, record).sendToTarget();
  }

  @Override
  public void onDataCompleted(IJsonObject probeConfig, JsonElement checkpoint) {
    // TODO Figure out what to do with continuations of probes, if anything

  }
}