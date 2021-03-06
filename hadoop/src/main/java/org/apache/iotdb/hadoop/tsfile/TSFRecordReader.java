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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.hadoop.tsfile;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.iotdb.hadoop.fileSystem.HDFSInput;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.read.ReadOnlyTsFile;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Field;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.expression.QueryExpression;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Yuan Tian
 */
public class TSFRecordReader extends RecordReader<NullWritable, MapWritable> implements IReaderSet {

  private static final Logger logger = LoggerFactory.getLogger(TSFRecordReader.class);

  /**
   * all
   */
  private List<QueryDataSet> dataSetList = new ArrayList<>();
  /**
   * List for name of devices. The order corresponds to the order of dataSetList.
   * Means that deviceIdList[i] is the name of device for dataSetList[i].
   */
  private List<String> deviceIdList = new ArrayList<>();
  private List<Field> fields = null;
  /**
   * The index of QueryDataSet that is currently processed
   */
  private  int currentIndex = 0;
  private long timestamp = 0;
  private boolean isReadDeviceId = false;
  private boolean isReadTime = false;
  private TsFileSequenceReader reader;
  private List<String> measurementIds;


  @Override
  public void initialize(InputSplit split, TaskAttemptContext context)
      throws IOException {
    if (split instanceof TSFInputSplit) {
      initialize((TSFInputSplit) split, context.getConfiguration(), this, dataSetList, deviceIdList);
    }
    else {
      logger.error("The InputSplit class is not {}, the class is {}", TSFInputSplit.class.getName(),
              split.getClass().getName());
      throw new InternalError(String.format("The InputSplit class is not %s, the class is %s",
              TSFInputSplit.class.getName(), split.getClass().getName()));
    }
  }

  public static void initialize(TSFInputSplit split, Configuration configuration, IReaderSet readerSet, List<QueryDataSet> dataSetList, List<String> deviceIdList) throws IOException  {
      org.apache.hadoop.fs.Path path = split.getPath();
      List<TSFInputSplit.ChunkGroupInfo> chunkGroupInfoList = split.getChunkGroupInfoList();
      TsFileSequenceReader reader = new TsFileSequenceReader(new HDFSInput(path, configuration));
      readerSet.setReader(reader);
      // Get the read columns and filter information

      List<String> deviceIds = TSFInputFormat.getReadDeviceIds(configuration);
      if (deviceIds == null) {
        deviceIds = initDeviceIdList(chunkGroupInfoList);
      }
      List<String> measurementIds = TSFInputFormat.getReadMeasurementIds(configuration);
      if (measurementIds == null) {
        measurementIds = initSensorIdList(chunkGroupInfoList);
      }
      readerSet.setMeasurementIds(measurementIds);
      logger.info("deviceIds:" + deviceIds);
      logger.info("Sensors:" + measurementIds);


      readerSet.setReadDeviceId(TSFInputFormat.getReadDeviceId(configuration));
      readerSet.setReadTime(TSFInputFormat.getReadTime(configuration));

      ReadOnlyTsFile queryEngine = new ReadOnlyTsFile(reader);
      for (TSFInputSplit.ChunkGroupInfo chunkGroupInfo : chunkGroupInfoList) {
        String deviceId = chunkGroupInfo.getDeviceId();
        if (deviceIds.contains(deviceId)) {
          List<Path> paths = measurementIds.stream()
                  .map(measurementId -> new Path(deviceId + TsFileConstant.PATH_SEPARATOR + measurementId))
                  .collect(toList());
          QueryExpression queryExpression = QueryExpression.create(paths, null);
          QueryDataSet dataSet = queryEngine.query(queryExpression,
                  chunkGroupInfo.getStartOffset(), chunkGroupInfo.getEndOffset());
          dataSetList.add(dataSet);
          deviceIdList.add(deviceId);
        }
      }
  }

  private static List<String> initDeviceIdList(List<TSFInputSplit.ChunkGroupInfo> chunkGroupInfoList) {
    return chunkGroupInfoList.stream()
            .map(TSFInputSplit.ChunkGroupInfo::getDeviceId)
            .distinct()
            .collect(toList());
  }

  private static List<String> initSensorIdList(List<TSFInputSplit.ChunkGroupInfo> chunkGroupInfoList) {
    return chunkGroupInfoList.stream()
            .flatMap(chunkGroupMetaData -> Arrays.stream(chunkGroupMetaData.getMeasurementIds()))
            .distinct()
            .collect(toList());
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    while (currentIndex < dataSetList.size()) {
      if (!dataSetList.get(currentIndex).hasNext()) {
        currentIndex++;
      }
      else {
        RowRecord rowRecord = dataSetList.get(currentIndex).next();
        fields = rowRecord.getFields();
        timestamp = rowRecord.getTimestamp();
        return true;
      }
    }
    return false;
  }

  @Override
  public NullWritable getCurrentKey() throws IOException, InterruptedException {
    return NullWritable.get();
  }

  @Override
  public MapWritable getCurrentValue() throws InterruptedException {
    return getCurrentValue(deviceIdList, currentIndex, timestamp, isReadTime, isReadDeviceId, fields, measurementIds);
  }

  public static MapWritable getCurrentValue(List<String> deviceIdList, int currentIndex,
                                            long timestamp, boolean isReadTime,
                                            boolean isReadDeviceId, List<Field> fields,
                                            List<String> measurementIds)  throws InterruptedException {
    MapWritable mapWritable = new MapWritable();
    Text deviceIdText = new Text(deviceIdList.get(currentIndex));
    LongWritable time = new LongWritable(timestamp);

    if (isReadTime) { // time needs to be written into value
      mapWritable.put(new Text("time_stamp"), time);
    }
    if (isReadDeviceId) { // deviceId need to be written into value
      mapWritable.put(new Text("device_id"), deviceIdText);
    }

    readFieldsValue(mapWritable, fields, measurementIds);

    return mapWritable;
  }

  /**
   * Read from current fields value
   * @param mapWritable where to write
   * @throws InterruptedException
   */
  public static void readFieldsValue(MapWritable mapWritable, List<Field> fields, List<String> measurementIds) throws InterruptedException {
    int index = 0;
    for (Field field : fields) {
      if (field.getDataType() == null) {
        logger.info("Current value is null");
        mapWritable.put(new Text(measurementIds.get(index)), NullWritable.get());
      } else {
        switch (field.getDataType()) {
          case INT32:
            mapWritable.put(new Text(measurementIds.get(index)), new IntWritable(field.getIntV()));
            break;
          case INT64:
            mapWritable.put(new Text(measurementIds.get(index)), new LongWritable(field.getLongV()));
            break;
          case FLOAT:
            mapWritable.put(new Text(measurementIds.get(index)), new FloatWritable(field.getFloatV()));
            break;
          case DOUBLE:
            mapWritable.put(new Text(measurementIds.get(index)), new DoubleWritable(field.getDoubleV()));
            break;
          case BOOLEAN:
            mapWritable.put(new Text(measurementIds.get(index)), new BooleanWritable(field.getBoolV()));
            break;
          case TEXT:
            mapWritable.put(new Text(measurementIds.get(index)), new Text(field.getBinaryV().getStringValue()));
            break;
          default:
            logger.error("The data type is not support {}", field.getDataType());
            throw new InterruptedException(
                    String.format("The data type %s is not support ", field.getDataType()));
        }
      }
      index++;
    }
  }

  @Override
  public float getProgress() throws IOException, InterruptedException {
    return 0;
  }

  @Override
  public void close() throws IOException {
    dataSetList = null;
    deviceIdList = null;
    reader.close();
  }

  @Override
  public void setReader(TsFileSequenceReader reader) {
    this.reader = reader;
  }

  @Override
  public void setMeasurementIds(List<String> measurementIds) {
    this.measurementIds = measurementIds;
  }

  @Override
  public void setReadDeviceId(boolean isReadDeviceId) {
    this.isReadDeviceId = isReadDeviceId;
  }

  @Override
  public void setReadTime(boolean isReadTime) {
    this.isReadTime = isReadTime;
  }
}
